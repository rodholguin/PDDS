package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.service.algorithm.RoutePlanningSupport;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class WarmupService {

    private static final Logger log = LoggerFactory.getLogger(WarmupService.class);
    private static final int PLAN_BATCH_SIZE = 64;
    private static final int WARMUP_SPEED = 20;

    private final ShipmentRepository shipmentRepository;
    private final RoutePlannerService routePlannerService;
    private final FlightScheduleService flightScheduleService;
    private final SimulationEngineService simulationEngineService;
    private final SimulationRuntimeService runtimeService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public WarmupService(
            ShipmentRepository shipmentRepository,
            RoutePlannerService routePlannerService,
            FlightScheduleService flightScheduleService,
            SimulationEngineService simulationEngineService,
            SimulationRuntimeService runtimeService,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager
    ) {
        this.shipmentRepository = shipmentRepository;
        this.routePlannerService = routePlannerService;
        this.flightScheduleService = flightScheduleService;
        this.simulationEngineService = simulationEngineService;
        this.runtimeService = runtimeService;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean requiresWarmup(SimulationConfig config, LocalDateTime periodStart) {
        if (config == null || config.getScenario() != SimulationScenario.PERIOD_SIMULATION) {
            return false;
        }
        LocalDate projectedFrom = config.getProjectedFrom();
        if (projectedFrom == null || periodStart == null) {
            return false;
        }
        return projectedFrom.atStartOfDay().isBefore(periodStart);
    }

    public WarmupResult runWarmup(SimulationConfig config, LocalDateTime periodStart) {
        LocalDateTime warmupFrom = config.getProjectedFrom().atStartOfDay();

        long total = shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(warmupFrom, periodStart);
        log.info("Warmup iniciado: warmupFrom={} periodStart={} total={}", warmupFrom, periodStart, total);
        runtimeService.markWarmupStarted(total,
                "Preparando simulación: planificando " + total + " envíos previos al período...");
        runtimeService.setSimulationTime(warmupFrom);

        flightScheduleService.ensureFlightsForWindow(warmupFrom, periodStart.plusDays(3));

        long planned = planPhase(warmupFrom, periodStart, total);

        // Set plannedThrough = periodStart so tick-phase ticks are not blocked by the
        // period-planning wait guard in SimulationEngineService.executeTick().
        runtimeService.updatePeriodPlanningProgress(periodStart, 0L,
                "Preparando simulación: iniciando ejecución acelerada...");

        long ticks = tickPhase(config, warmupFrom, periodStart);

        runtimeService.markWarmupCompleted(
                "Warmup completado — " + planned + " rutas planificadas, " + ticks + " ticks");
        log.info("Warmup completado: planned={} ticks={} finalTime={}",
                planned, ticks, runtimeService.currentSimulationTime().orElse(null));

        return new WarmupResult(total, planned, ticks, warmupFrom, periodStart);
    }

    private long planPhase(LocalDateTime warmupFrom, LocalDateTime periodStart, long total) {
        List<Airport> airports = routePlannerService.allAirports();
        List<Flight> flights = routePlannerService.schedulableFlightsForExistingWindow(
                warmupFrom, periodStart.plusDays(3));
        RoutePlanningSupport.PlanningFlightIndex flightIndex =
                RoutePlanningSupport.buildPlanningFlightIndex(flights);

        long planned = 0L;
        long failed = 0L;

        while (true) {
            long[] outcome = transactionTemplate.execute(status -> {
                List<Shipment> batch = shipmentRepository.findPendingWithoutRouteForPlanningInPeriod(
                        warmupFrom, periodStart, PLAN_BATCH_SIZE);
                if (batch.isEmpty()) {
                    return new long[]{-1L, -1L};
                }

                List<RankedShipment> ranked = new ArrayList<>(batch.size());
                for (Shipment s : batch) {
                    ranked.add(new RankedShipment(s, heuristicDifficulty(s)));
                }
                ranked.sort(Comparator
                        .comparingInt(RankedShipment::difficulty)
                        .thenComparing(r -> r.shipment().getRegistrationDate(),
                                Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(r -> r.shipment().getDeadline(),
                                Comparator.nullsLast(LocalDateTime::compareTo)));

                long batchOk = 0L;
                long batchFail = 0L;
                for (RankedShipment rs : ranked) {
                    try {
                        List<TravelStop> stops = routePlannerService.planShipment(
                                rs.shipment(),
                                "Genetic Algorithm",
                                flightIndex,
                                airports,
                                true,
                                false,
                                true
                        );
                        if (stops != null && !stops.isEmpty()) batchOk++;
                        else batchFail++;
                    } catch (Exception ex) {
                        batchFail++;
                        log.debug("Warmup plan: envio {}: {}", rs.shipment().getId(), ex.getMessage());
                    }
                }
                entityManager.flush();
                entityManager.clear();
                return new long[]{batchOk, batchFail};
            });

            if (outcome == null || outcome[0] < 0L) break;
            planned += outcome[0];
            failed += outcome[1];
            runtimeService.updateBootstrapProgress(planned + failed,
                    "Preparando simulación: " + (planned + failed) + " / " + total + " rutas planificadas");
        }

        log.info("Warmup fase-plan: planned={} failed={}", planned, failed);
        return planned;
    }

    private long tickPhase(SimulationConfig config, LocalDateTime warmupFrom, LocalDateTime periodStart) {
        int originalSpeed = runtimeService.currentSpeed();
        runtimeService.setSpeed(WARMUP_SPEED);

        long totalWarmupSeconds = Math.max(1L, ChronoUnit.SECONDS.between(warmupFrom, periodStart));
        long totalShipments = runtimeService.bootstrapTotalShipments();

        try {
            long ticks = 0L;
            while (true) {
                LocalDateTime current = runtimeService.currentSimulationTime().orElse(null);
                if (current == null || !current.isBefore(periodStart)) break;

                // If the remaining gap is smaller than one tick, clamp to periodStart and stop.
                // This avoids overshooting periodStart and activating shipments that belong
                // to the real simulation period.
                long secondsRemaining = ChronoUnit.SECONDS.between(current, periodStart);
                long secondsPerTick = runtimeService.simulationSecondsPerTick(config);
                if (secondsRemaining <= secondsPerTick) {
                    runtimeService.setSimulationTime(periodStart);
                    break;
                }

                simulationEngineService.executeHeadlessTick(config);
                ticks++;

                LocalDateTime after = runtimeService.currentSimulationTime().orElse(current);
                long elapsedSeconds = ChronoUnit.SECONDS.between(warmupFrom, after);
                long progress = totalShipments > 0
                        ? Math.min(totalShipments, totalShipments * elapsedSeconds / totalWarmupSeconds)
                        : 0L;
                runtimeService.updateBootstrapProgress(progress,
                        "Preparando simulación: procesando " + after.toLocalDate() + " / " + periodStart.toLocalDate());
            }
            return ticks;
        } finally {
            runtimeService.setSpeed(originalSpeed);
        }
    }

    private int heuristicDifficulty(Shipment shipment) {
        if (shipment == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return 4;
        }
        if (shipment.getOriginAirport().getId() != null
                && shipment.getOriginAirport().getId().equals(shipment.getDestinationAirport().getId())) {
            return 0;
        }
        boolean sameContinent = shipment.getOriginAirport().getContinent() == shipment.getDestinationAirport().getContinent();
        long slackHours = shipment.getDeadline() == null || shipment.getRegistrationDate() == null
                ? Long.MAX_VALUE
                : ChronoUnit.HOURS.between(shipment.getRegistrationDate(), shipment.getDeadline());
        if (sameContinent && slackHours >= 12) return 1;
        if (sameContinent) return 2;
        return slackHours <= 24 ? 4 : 3;
    }

    private record RankedShipment(Shipment shipment, int difficulty) {}

    public record WarmupResult(
            long totalShipments,
            long plannedShipments,
            long ticksExecuted,
            LocalDateTime warmupFrom,
            LocalDateTime warmupTo
    ) {}
}
