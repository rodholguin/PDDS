package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.algorithm.RoutePlanningSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SimulationAsyncOperationsService {

    private static final Logger log = LoggerFactory.getLogger(SimulationAsyncOperationsService.class);
    private static final int DAY_TO_DAY_BATCH = 8;
    private static final int COLLAPSE_BATCH = 8;
    private static final int PERIOD_BATCH = 16;
    private static final long OVERDUE_SCAN_MS = 10_000L;

    private final SimulationConfigRepository simulationConfigRepository;
    private final SimulationRuntimeService runtimeService;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final RoutePlannerService routePlannerService;
    private final OperationalAlertService operationalAlertService;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean planningInProgress = new AtomicBoolean(false);
    private final AtomicBoolean overdueScanInProgress = new AtomicBoolean(false);

    public SimulationAsyncOperationsService(
            SimulationConfigRepository simulationConfigRepository,
            SimulationRuntimeService runtimeService,
            ShipmentRepository shipmentRepository,
            TravelStopRepository travelStopRepository,
            RoutePlannerService routePlannerService,
            OperationalAlertService operationalAlertService,
            TransactionTemplate transactionTemplate
    ) {
        this.simulationConfigRepository = simulationConfigRepository;
        this.runtimeService = runtimeService;
        this.shipmentRepository = shipmentRepository;
        this.travelStopRepository = travelStopRepository;
        this.routePlannerService = routePlannerService;
        this.operationalAlertService = operationalAlertService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 250)
    public void processPlanningBacklog() {
        if (!planningInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            SimulationConfig config = getConfig();
            if (!shouldRunPlanning(config)) {
                return;
            }
            if (config.getScenario() == SimulationScenario.PERIOD_SIMULATION) {
                processPeriodPlanningBacklog(config);
                return;
            }
            LocalDateTime horizon = runtimeService.currentSimulationTime().orElse(null);
            if (horizon == null) {
                return;
            }

            int batchSize = planningBatchForScenario(config.getScenario());
            LocalDateTime windowStart = horizon.toLocalDate().atStartOfDay();
            long backlog = shipmentRepository.countPendingWithoutRouteForPlanningInWindow(windowStart, horizon.plusSeconds(1));
            List<Shipment> pending = shipmentRepository.findPendingWithoutRouteForPlanningInWindow(windowStart, horizon, batchSize);
            if (pending.isEmpty()) {
                pending = shipmentRepository.findPendingWithoutRouteForPlanning(horizon, batchSize);
                backlog = pending.isEmpty() ? 0L : Math.max(backlog, pending.size());
            }
            if (pending.isEmpty()) {
                return;
            }

            long startedAt = System.nanoTime();
            LocalDateTime minRegistration = pending.stream()
                    .map(Shipment::getRegistrationDate)
                    .filter(java.util.Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(horizon);
            LocalDateTime maxRegistration = pending.stream()
                    .map(Shipment::getRegistrationDate)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(horizon);

            List<Flight> flights = routePlannerService.availableFlightsForWindow(minRegistration, maxRegistration.plusDays(3));
            RoutePlanningSupport.PlanningFlightIndex flightIndex = RoutePlanningSupport.buildPlanningFlightIndex(flights);
            List<Airport> airports = routePlannerService.allAirports();
            String algorithmName = routePlannerService.activeAlgorithmName();

            int planned = 0;
            int failed = 0;
            for (Long shipmentId : pending.stream().map(Shipment::getId).toList()) {
                try {
                    List<TravelStop> stops = transactionTemplate.execute(status -> {
                        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
                        if (shipment == null || shipment.getStatus() != ShipmentStatus.PENDING || travelStopRepository.existsByShipmentId(shipmentId)) {
                            return List.<TravelStop>of();
                        }
                        return routePlannerService.planShipment(
                                shipment,
                                algorithmName,
                                flightIndex,
                                airports,
                                true,
                                true,
                                false
                        );
                    });
                    if (stops == null) {
                        failed++;
                    } else if (!stops.isEmpty()) {
                        planned++;
                        applyReservedLoadSnapshot(stops);
                    }
                } catch (Exception ex) {
                    failed++;
                    log.debug("Planning worker: no se pudo planificar envio {}: {}", shipmentId, ex.getMessage());
                }
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (elapsedMs > 500L || planned > 0 || failed > 0 || backlog > batchSize) {
                log.info(
                        "Planning worker escenario={} backlog={} batch={} planned={} failed={} elapsed={} ms",
                        config.getScenario(),
                        backlog,
                        pending.size(),
                        planned,
                        failed,
                        elapsedMs
                );
            }
        } finally {
            planningInProgress.set(false);
        }
    }

    @Scheduled(fixedDelay = OVERDUE_SCAN_MS)
    public void processOverdueShipments() {
        if (!overdueScanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            SimulationConfig config = getConfig();
            if (!shouldRunMaintenance(config)) {
                return;
            }
            LocalDateTime now = runtimeService.currentSimulationTime().orElse(null);
            if (now == null) {
                return;
            }

            long startedAt = System.nanoTime();
            Integer marked = transactionTemplate.execute(status -> {
                int updated = shipmentRepository.markActiveAsDelayedBefore(now);
                if (updated <= 0) {
                    return updated;
                }
                int alertBatch = Math.min(Math.max(10, updated), 64);
                shipmentRepository.findDelayedOverdueShipmentsWithoutActiveAlert(
                                now,
                                "OVERDUE_SHIPMENT",
                                List.of(com.tasfb2b.model.OperationalAlertStatus.PENDING, com.tasfb2b.model.OperationalAlertStatus.IN_REVIEW),
                                PageRequest.of(0, alertBatch)
                        ).forEach(shipment -> operationalAlertService.ensureShipmentAlert(
                                shipment,
                                "OVERDUE_SHIPMENT",
                                "El envío excedió su plazo operativo y quedó marcado como delayed"
                        ));
                return updated;
            });

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if ((marked != null && marked > 0) || elapsedMs > 250L) {
                log.info("Overdue worker marked={} elapsed={} ms", marked == null ? 0 : marked, elapsedMs);
            }
        } finally {
            overdueScanInProgress.set(false);
        }
    }

    public boolean isPlanningInProgress() {
        return planningInProgress.get();
    }

    public boolean isOverdueScanInProgress() {
        return overdueScanInProgress.get();
    }

    private boolean shouldRunPlanning(SimulationConfig config) {
        return shouldRunMaintenance(config)
                && !runtimeService.isBootstrapping();
    }

    private boolean shouldRunMaintenance(SimulationConfig config) {
        return config != null
                && Boolean.TRUE.equals(config.getIsRunning())
                && !runtimeService.isPaused()
                && !runtimeService.isResetting();
    }

    private int planningBatchForScenario(SimulationScenario scenario) {
        if (scenario == SimulationScenario.PERIOD_SIMULATION) {
            return PERIOD_BATCH;
        }
        if (scenario == SimulationScenario.COLLAPSE_TEST) {
            return COLLAPSE_BATCH;
        }
        return DAY_TO_DAY_BATCH;
    }

    private void processPeriodPlanningBacklog(SimulationConfig config) {
        LocalDateTime periodStart = config.getEffectiveScenarioStartAt() != null
                ? config.getEffectiveScenarioStartAt()
                : config.getScenarioStartAt();
        if (periodStart == null) {
            return;
        }
        LocalDateTime periodEnd = periodStart.plusDays(Math.max(1, config.getSimulationDays() == null ? 5 : config.getSimulationDays()));
        long backlog = shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(periodStart, periodEnd);
        if (backlog <= 0L) {
            runtimeService.updatePeriodPlanningProgress(periodEnd, 0L, "Planificacion incremental del periodo completada");
            return;
        }

        int batchSize = planningBatchForScenario(config.getScenario());
        List<Shipment> pending = shipmentRepository.findPendingWithoutRouteForPlanningInPeriod(periodStart, periodEnd, batchSize);
        if (pending.isEmpty()) {
            runtimeService.updatePeriodPlanningProgress(periodEnd, backlog, "Esperando siguiente lote de planificacion del periodo");
            return;
        }

        long startedAt = System.nanoTime();
        LocalDateTime minRegistration = pending.stream()
                .map(Shipment::getRegistrationDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(periodStart);
        LocalDateTime maxRegistration = pending.stream()
                .map(Shipment::getRegistrationDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(periodStart);

        List<Flight> flights = routePlannerService.schedulableFlightsForExistingWindow(minRegistration, periodEnd.plusDays(3));
        RoutePlanningSupport.PlanningFlightIndex flightIndex = RoutePlanningSupport.buildPlanningFlightIndex(flights);
        List<Airport> airports = routePlannerService.allAirports();

        int planned = 0;
        int failed = 0;
        for (Long shipmentId : pending.stream().map(Shipment::getId).toList()) {
            try {
                List<TravelStop> stops = transactionTemplate.execute(status -> {
                    Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
                    if (shipment == null || shipment.getStatus() != ShipmentStatus.PENDING || travelStopRepository.existsByShipmentId(shipmentId)) {
                        return List.<TravelStop>of();
                    }
                    return routePlannerService.planShipment(
                            shipment,
                            routePlannerService.activeAlgorithmName(),
                            flightIndex,
                            airports,
                            true,
                            false,
                            true
                    );
                });
                if (stops == null) {
                    failed++;
                } else if (!stops.isEmpty()) {
                    planned++;
                    applyReservedLoadSnapshot(stops);
                }
            } catch (Exception ex) {
                failed++;
                log.debug("Planning worker periodo: no se pudo planificar envio {}: {}", shipmentId, ex.getMessage());
            }
        }

        long remainingBacklog = shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(periodStart, periodEnd);
        LocalDateTime earliestUnplanned = shipmentRepository.findEarliestUnplannedRegistrationInPeriod(periodStart, periodEnd);
        runtimeService.updatePeriodPlanningProgress(
                earliestUnplanned == null ? periodEnd : earliestUnplanned,
                remainingBacklog,
                remainingBacklog <= 0L
                        ? "Planificacion incremental del periodo completada"
                        : "Planificacion incremental del periodo en progreso"
        );

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        runtimeService.recordPeriodPlanningBatch(planned, failed, elapsedMs);
        if (elapsedMs > 500L || planned > 0 || failed > 0 || remainingBacklog > batchSize) {
            log.info(
                    "Planning worker escenario={} backlog={} batch={} planned={} failed={} elapsed={} ms",
                    config.getScenario(),
                    remainingBacklog,
                    pending.size(),
                    planned,
                    failed,
                    elapsedMs
            );
        }
    }

    private void applyReservedLoadSnapshot(List<TravelStop> stops) {
        if (stops == null || stops.isEmpty()) {
            return;
        }
        Shipment shipment = stops.get(0).getShipment();
        int luggage = shipment == null || shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        if (luggage <= 0) {
            return;
        }
        Map<Long, Integer> perFlight = new LinkedHashMap<>();
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null || flight.getId() == null) {
                continue;
            }
            perFlight.merge(flight.getId(), luggage, Integer::sum);
        }
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null || flight.getId() == null) {
                continue;
            }
            Integer delta = perFlight.remove(flight.getId());
            if (delta != null) {
                flight.setReservedLoad(Math.min(flight.getMaxCapacity(), flight.getReservedLoad() + delta));
            }
        }
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : simulationConfigRepository.save(SimulationConfig.builder().build());
    }
}
