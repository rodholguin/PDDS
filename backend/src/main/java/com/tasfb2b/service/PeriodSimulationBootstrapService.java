package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

@Service
public class PeriodSimulationBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(PeriodSimulationBootstrapService.class);
    private static final int BATCH_SIZE = 64;
    // Semilla SÍNCRONA mínima al iniciar: solo se planifican los envíos de las primeras 2h
    // para que "Iniciar" responda rápido. El planner asíncrono (processSimPlanningBacklog,
    // ~1k envíos/s) completa el resto de inmediato, y el guard de plannedThrough evita que el
    // reloj adelante a la planificación. Antes eran 6h (~7s de bloqueo en el arranque).
    private final ShipmentRepository shipmentRepository;
    private final FlightRepository flightRepository;
    private final RoutePlannerService routePlannerService;
    private final FlightScheduleService flightScheduleService;
    private final SimulationRuntimeService runtimeService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public PeriodSimulationBootstrapService(
            ShipmentRepository shipmentRepository,
            FlightRepository flightRepository,
            RoutePlannerService routePlannerService,
            FlightScheduleService flightScheduleService,
            SimulationRuntimeService runtimeService,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager
    ) {
        this.shipmentRepository = shipmentRepository;
        this.flightRepository = flightRepository;
        this.routePlannerService = routePlannerService;
        this.flightScheduleService = flightScheduleService;
        this.runtimeService = runtimeService;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean requiresBootstrap(SimulationConfig config) {
        return config != null
                && (config.getScenario() == SimulationScenario.PERIOD_SIMULATION
                    || config.getScenario() == SimulationScenario.COLLAPSE_TEST);
    }

    public BootstrapResult seedPeriod(SimulationConfig config, LocalDateTime periodStart) {
        if (!requiresBootstrap(config) || periodStart == null) {
            return new BootstrapResult(0L, 0L, 0L, periodStart, periodStart);
        }

        LocalDateTime periodEnd = runtimeService.resolveScenarioEnd(config);
        if (periodEnd == null) {
            periodEnd = periodStart.plusDays(Math.max(1, config.getSimulationDays() == null ? 5 : config.getSimulationDays()));
        }
        LocalDateTime seedTargetCandidate = periodStart.plusSeconds(runtimeService.consumptionWindowSeconds(config));
        final LocalDateTime seedTarget = seedTargetCandidate.isAfter(periodEnd) ? periodEnd : seedTargetCandidate;
        long total = shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(periodStart, seedTarget);
        runtimeService.markBootstrapStarted(total, "Preplanificando rutas del periodo");
        runtimeService.markPeriodPlanningActive(seedTarget, shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(periodStart, periodEnd),
                "Semilla inicial del periodo en preparación");

        List<Airport> airports = routePlannerService.allAirports();
        // La semilla SOLO planifica envios registrados en [periodStart, seedTarget] (seedTarget = +6h), asi
        // que basta cargar vuelos de esa ventana + holgura de transito. Antes cargaba [periodStart, periodEnd+3d]
        // = TODO el periodo (en COLLAPSE periodEnd=fin de datos 2029); con millones de clones de vuelos
        // acumulados de corridas previas eso reventaba el heap (OutOfMemoryError) al arrancar. El planner
        // async ya carga por ventana de lote para el resto del periodo.
        List<Flight> flights = routePlannerService.schedulableFlightsForExistingWindow(periodStart, seedTarget.plusDays(3));
        com.tasfb2b.service.algorithm.RoutePlanningSupport.PlanningFlightIndex flightIndex =
                com.tasfb2b.service.algorithm.RoutePlanningSupport.buildPlanningFlightIndex(flights);
        long planned = 0L;
        long failed = 0L;

        while (true) {
            long[] batchOutcome = transactionTemplate.execute(status -> {
                List<Shipment> batch = shipmentRepository.findPendingWithoutRouteForPlanningInPeriod(periodStart, seedTarget, BATCH_SIZE);
                if (batch.isEmpty()) {
                    return new long[]{-1L, -1L};
                }

                List<RankedShipment> rankedBatch = new ArrayList<>(batch.size());
                for (Shipment shipment : batch) {
                    rankedBatch.add(new RankedShipment(shipment, heuristicDifficulty(shipment)));
                }

                List<RankedShipment> orderedBatch = rankedBatch.stream()
                        .sorted(Comparator
                                .comparingInt(RankedShipment::difficulty)
                                .thenComparing(ranked -> ranked.shipment().getRegistrationDate(), Comparator.nullsLast(LocalDateTime::compareTo))
                                .thenComparing(ranked -> ranked.shipment().getDeadline(), Comparator.nullsLast(LocalDateTime::compareTo)))
                        .toList();

                long batchPlanned = 0L;
                long batchFailed = 0L;
                for (RankedShipment ranked : orderedBatch) {
                    Shipment shipment = ranked.shipment();
                    try {
                        List<com.tasfb2b.model.TravelStop> stops = routePlannerService.planShipment(
                                shipment,
                                "Genetic Algorithm",
                                flightIndex,
                                airports,
                                true,
                                false,
                                true
                        );
                        if (!stops.isEmpty()) {
                            batchPlanned++;
                        } else {
                            batchFailed++;
                        }
                    } catch (Exception ex) {
                        batchFailed++;
                        log.debug("Bootstrap: no se pudo planificar envio {}: {}", shipment.getId(), ex.getMessage());
                    }
                }

                entityManager.flush();
                entityManager.clear();
                return new long[]{batchPlanned, batchFailed};
            });
            if (batchOutcome == null || batchOutcome[0] < 0L) {
                break;
            }
            planned += batchOutcome == null ? 0L : batchOutcome[0];
            failed += batchOutcome == null ? 0L : batchOutcome[1];
            runtimeService.updateBootstrapProgress(planned + failed, "Preplanificados " + (planned + failed) + " de " + total + " envios");
        }

        LocalDateTime plannedThrough = shipmentRepository.findEarliestUnplannedRegistrationInPeriod(periodStart, periodEnd);
        runtimeService.updatePeriodPlanningProgress(
                plannedThrough == null ? periodEnd : plannedThrough,
                shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(periodStart, periodEnd),
                "Semilla inicial completada: " + planned + " ok, " + failed + " fallidos"
        );
        runtimeService.markBootstrapCompleted("Semilla inicial completada: " + planned + " ok, " + failed + " fallidos");
        return new BootstrapResult(total, planned, failed, seedTarget, plannedThrough == null ? periodEnd : plannedThrough);
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
                : java.time.temporal.ChronoUnit.HOURS.between(shipment.getRegistrationDate(), shipment.getDeadline());

        if (sameContinent && slackHours >= 12) {
            return 1;
        }
        if (sameContinent) {
            return 2;
        }
        return slackHours <= 24 ? 4 : 3;
    }

    private record RankedShipment(Shipment shipment, int difficulty) {}

    public record BootstrapResult(long totalShipments,
                                  long plannedShipments,
                                  long failedShipments,
                                  LocalDateTime seedTarget,
                                  LocalDateTime plannedThrough) {}
}
