package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentSource;
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
    private static final int DAY_TO_DAY_BATCH = 64;
    private static final int COLLAPSE_BATCH = 16;
    private static final int PERIOD_BATCH = 16;
    private static final long OVERDUE_SCAN_MS = 10_000L;
    // El planner procesa varios lotes seguidos hasta agotar este presupuesto por invocacion (en vez de 1
    // lote pequeño cada 250ms), reusando el indice de vuelos cacheado → throughput mucho mayor.
    private static final long PLANNING_BUDGET_NANOS = 3_000_000_000L;
    // La ventana de vuelos del indice cubre el registro del lote + este lapso, y se reusa mientras siga
    // cubriendo los lotes siguientes (que avanzan en tiempo de registro) → amortiza el costoso load.
    private static final long FLIGHT_INDEX_SPAN_DAYS = 2L;

    private final SimulationConfigRepository simulationConfigRepository;
    private final SimulationRuntimeService runtimeService;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final RoutePlannerService routePlannerService;
    private final OperationalAlertService operationalAlertService;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean planningInProgress = new AtomicBoolean(false);
    private final AtomicBoolean overdueScanInProgress = new AtomicBoolean(false);

    // Caché del índice de vuelos. Reconstruir la ventana de vuelos + el índice por lote es el costo
    // DOMINANTE del planner (con millones de clones de vuelos materializados). Se reusa entre lotes e
    // invocaciones mientras la ventana cubra el registro del lote → el load se amortiza sobre miles de
    // envíos en vez de 8-16. Solo lo usa el hilo del planner (single-flight via planningInProgress).
    private List<Airport> cachedAirports;
    private RoutePlanningSupport.PlanningFlightIndex cachedFlightIndex;
    private LocalDateTime cachedIndexFrom;
    private LocalDateTime cachedIndexTo;
    private LocalDateTime cachedRunStartedAt;
    // El conteo del backlog es un seq-scan de millones (el rango cubre casi toda la tabla). Se cuenta UNA vez
    // (en frio) y se descuenta lo planificado cada lote (exacto: durante una corrida no entran envios nuevos).
    // -1 = frio (recomputar). Solo lo usa el hilo del planner.
    private volatile long cachedPlanningBacklog = -1L;

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

    private final java.util.concurrent.atomic.AtomicBoolean simPlanningInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Plan-ahead de la SIMULACIÓN (id=2), EN PARALELO a la operación viva. Corre como tarea separada del
     * planner del día a día ({@link #processPlanningBacklog()}, que atiende los envíos LIVE de id=1) para que
     * ambos planifiquen a la vez sin bloquearse. El plan-ahead usa el config del sim (su ventana) y el
     * plannedThrough global (solo el sim lo usa).
     */
    @Scheduled(fixedDelay = 750)
    public void processSimPlanningBacklog() {
        if (!simPlanningInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (runtimeService.isPaused() || runtimeService.isResetting() || runtimeService.isBootstrapping()) {
                return;
            }
            SimulationConfig sim = simulationConfigRepository.findFirstByScenarioInAndIsRunningTrue(java.util.List.of(
                    SimulationScenario.PERIOD_SIMULATION, SimulationScenario.COLLAPSE_TEST)).orElse(null);
            if (sim == null) {
                return;
            }
            processPeriodPlanningBacklog(sim);
        } finally {
            simPlanningInProgress.set(false);
        }
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
            if (config.getScenario() == SimulationScenario.PERIOD_SIMULATION
                    || config.getScenario() == SimulationScenario.COLLAPSE_TEST) {
                processPeriodPlanningBacklog(config);
                return;
            }
            LocalDateTime horizon = runtimeService.currentSimulationTime().orElse(null);
            if (horizon == null) {
                return;
            }

            int batchSize = planningBatchForScenario(config.getScenario());
            // DAY_TO_DAY (modo live): la OPERACIÓN empieza en la fecha de inicio ingresada (scenarioStart).
            // El planner NUNCA debe tocar envíos registrados ANTES de esa fecha. Ese backlog histórico se
            // colaba por el fallback sin cota inferior (findPendingWithoutRouteForPlanning) y metía "vuelos
            // antiguos" en la operación: envíos viejos ruteados sobre vuelos ya pasados que vencían al
            // instante. Cota inferior dura = inicio del escenario → arranque limpio en la fecha ingresada.
            LocalDateTime operationsStart = config.getEffectiveScenarioStartAt() != null
                    ? config.getEffectiveScenarioStartAt()
                    : config.getScenarioStartAt();
            LocalDateTime dayStart = horizon.toLocalDate().atStartOfDay();
            LocalDateTime windowStart = (operationsStart != null && operationsStart.isAfter(dayStart))
                    ? operationsStart : dayStart;
            // DAY_TO_DAY opera SOLO envíos LIVE — los HISTORICAL del dataset (registrados "hoy") quedan fuera.
            long backlog = shipmentRepository.countPendingLiveWithoutRouteInWindow(windowStart, horizon.plusSeconds(1));
            List<Shipment> pending = shipmentRepository.findPendingLiveWithoutRouteInWindow(windowStart, horizon, batchSize);
            if (pending.isEmpty() && operationsStart != null) {
                // Catch-up de días previos SOLO dentro de la operación [inicio, reloj] — jamás antes del inicio.
                pending = shipmentRepository.findPendingLiveWithoutRouteInWindow(operationsStart, horizon, batchSize);
                backlog = pending.isEmpty() ? 0L : Math.max(backlog, pending.size());
            } else if (pending.isEmpty()) {
                pending = shipmentRepository.findPendingLiveWithoutRoute(horizon, batchSize);
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
                ShipmentSource source = config.getScenario() == SimulationScenario.DAY_TO_DAY
                        ? ShipmentSource.LIVE
                        : ShipmentSource.HISTORICAL;
                int updated = shipmentRepository.markActiveAsDelayedBeforeBySource(now, source);
                if (updated <= 0) {
                    return updated;
                }
                int alertBatch = Math.min(Math.max(10, updated), 64);
                shipmentRepository.findDelayedOverdueShipmentsWithoutActiveAlertBySource(
                                now,
                                source,
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
        LocalDateTime resolvedEnd = runtimeService.resolveScenarioEnd(config);
        final LocalDateTime scenarioEnd = resolvedEnd != null ? resolvedEnd
                : periodStart.plusDays(Math.max(1, config.getSimulationDays() == null ? 5 : config.getSimulationDays()));
        // FRONTERA de planificacion: arranca cada lote desde el ultimo punto planificado (no desde periodStart).
        // Antes, el find/count re-escaneaban TODO el prefijo ya planificado en cada lote (O(ya-planificado)) y la
        // planificacion se degradaba de <1s a 18-27s/lote al avanzar. Partir desde la frontera lo deja a costo
        // CONSTANTE. El backlog se cuenta por fecha de registro (indice) en vez del NOT EXISTS sobre travel_stop.
        LocalDateTime frontier = runtimeService.periodPlannedThrough()
                .filter(f -> f.isAfter(periodStart))
                .orElseGet(() -> {
                    LocalDateTime persistedNow = config.getRuntimeSimulatedNow();
                    if (persistedNow != null && persistedNow.isAfter(periodStart)) {
                        return persistedNow;
                    }
                    // La frontera vive en memoria (runtime map) y se PIERDE si el backend reinicia. Sin esto,
                    // tras un restart con datos a medio correr la frontera caeria a periodStart y el primer lote
                    // re-escanearia todo el prefijo ya planificado. La rederivamos UNA vez desde BD (al setear
                    // plannedThrough al final del lote, los siguientes lotes ya la leen del map → consulta unica).
                    LocalDateTime earliest = shipmentRepository.findEarliestUnplannedRegistrationInPeriod(periodStart, scenarioEnd);
                    return earliest == null ? scenarioEnd : earliest;
                });
        if (!frontier.isBefore(scenarioEnd)) {
            invalidateFlightIndexCache();
            runtimeService.updatePeriodPlanningProgress(scenarioEnd, 0L, "Planificacion incremental del periodo completada");
            return;
        }
        LocalDateTime currentSimTime = runtimeService.currentSimulationTime(config).orElse(frontier);
        long consumptionWindowSeconds = runtimeService.consumptionWindowSeconds(config);
        LocalDateTime leadTarget = currentSimTime.plusSeconds(consumptionWindowSeconds * 4L);
        if (frontier.isAfter(leadTarget) && frontier.isBefore(scenarioEnd)) {
            runtimeService.updatePeriodPlanningProgress(frontier, 0L, "Planificacion por adelantado lista");
            return;
        }
        LocalDateTime windowEnd = frontier.plusSeconds(consumptionWindowSeconds);
        if (leadTarget.isAfter(windowEnd)) {
            windowEnd = leadTarget;
        }
        if (windowEnd.isAfter(scenarioEnd)) {
            windowEnd = scenarioEnd;
        }

        int batchSize = planningBatchForScenario(config.getScenario());
        if (cachedAirports == null) {
            cachedAirports = routePlannerService.allAirports();
        }

        long startedAt = System.nanoTime();
        long budgetEnd = startedAt + PLANNING_BUDGET_NANOS;
        String algorithmName = routePlannerService.activeAlgorithmName();
        int planned = 0;
        int failed = 0;
        int processedBatches = 0;
        // Cursor que avanza con el high-water (maxRegistration) de cada lote. Cada find arranca desde aqui, asi
        // los lotes sucesivos NO re-saltan lo ya planificado y la frontera del proximo ciclo es O(1) (sin query).
        LocalDateTime planningCursor = frontier;

        // Loop con presupuesto de tiempo: procesa varios lotes seguidos por invocacion (en vez de 1 lote
        // pequeño cada 250ms), reusando el indice de vuelos cacheado mientras la ventana lo cubra. Asi el
        // costoso load de vuelos se amortiza sobre miles de envios y se eliminan los huecos entre ticks.
        do {
            List<Shipment> pending = shipmentRepository.findPendingWithoutRouteForPlanningInPeriod(planningCursor, windowEnd, batchSize);
            if (pending.isEmpty()) {
                planningCursor = windowEnd;
                break;
            }
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
            // Avanzar la frontera al high-water del lote: el proximo find (aqui o en la proxima invocacion)
            // arranca despues de lo recien planificado (el NOT EXISTS excluye estos) en vez de re-escanear.
            if (maxRegistration.isAfter(planningCursor)) {
                planningCursor = maxRegistration;
            }

            RoutePlanningSupport.PlanningFlightIndex flightIndex = flightIndexFor(config.getStartedAt(), minRegistration, maxRegistration);
            List<Airport> airports = cachedAirports;

            int plannedBefore = planned;
            for (Long shipmentId : pending.stream().map(Shipment::getId).toList()) {
                try {
                    List<TravelStop> stops = transactionTemplate.execute(status -> {
                        Shipment shipment = shipmentRepository.findByIdWithAirports(shipmentId).orElse(null);
                        // existsByShipmentId era redundante: la query del lote ya filtra NOT EXISTS travel_stop
                        // y el planner es de un solo hilo. Se elimina (1 query menos por envío).
                        if (shipment == null || shipment.getStatus() != ShipmentStatus.PENDING) {
                            return List.<TravelStop>of();
                        }
                        return routePlannerService.planShipment(
                                shipment,
                                algorithmName,
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
            processedBatches++;
            // Si el lote no produjo NINGUN avance (todo fallo/saltado), no insistir en bucle apretado.
            if (planned == plannedBefore) {
                break;
            }
        } while (System.nanoTime() < budgetEnd);

        // La frontera segura no es simplemente el high-water del lote: con muchos envios en la misma ventana,
        // un batch puede dejar pendientes con registrationDate anterior al cursor. Si publicamos ese cursor como
        // plannedThrough, el detector de colapso puede declarar "sin ruta" a un envio que aun no fue tomado por
        // el planner. Re-derivamos la frontera dentro de la ventana Sc: si queda un pendiente, plannedThrough es
        // exactamente ese primer timestamp; si no queda ninguno, la ventana esta realmente cubierta.
        LocalDateTime earliestUnplanned = shipmentRepository.findEarliestUnplannedRegistrationInPeriod(periodStart, windowEnd);
        LocalDateTime safePlannedThrough = earliestUnplanned == null ? planningCursor : earliestUnplanned;
        long remainingBacklog = earliestUnplanned == null
                ? 0L
                : shipmentRepository.countPendingWithoutRouteForPlanningInPeriod(earliestUnplanned, windowEnd);
        cachedPlanningBacklog = remainingBacklog;
        boolean scenarioCompleted = !safePlannedThrough.isBefore(scenarioEnd) && remainingBacklog <= 0L;
        runtimeService.updatePeriodPlanningProgress(
                safePlannedThrough,
                remainingBacklog,
                scenarioCompleted
                        ? "Planificacion incremental del periodo completada"
                        : "Planificacion programada en progreso (Sc="
                        + runtimeService.consumptionWindowSeconds(config) / 60L + " min)"
        );

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        runtimeService.recordPeriodPlanningBatch(planned, failed, elapsedMs);
        if (elapsedMs > 500L || planned > 0 || failed > 0 || remainingBacklog > batchSize) {
            log.info(
                    "Planning worker escenario={} ventanaHasta={} backlog={} batches={} planned={} failed={} elapsed={} ms",
                    config.getScenario(),
                    windowEnd,
                    remainingBacklog,
                    processedBatches,
                    planned,
                    failed,
                    elapsedMs
            );
        }
    }

    /**
     * Devuelve el índice de vuelos para la ventana del lote, reusando el caché mientras siga cubriendo
     * [minRegistration, maxRegistration + holgura]. Reconstruir la ventana + índice es el costo dominante;
     * como los lotes avanzan en tiempo de registro, una sola construcción sirve para ~FLIGHT_INDEX_SPAN_DAYS
     * de avance. Se invalida al cambiar de corrida (periodStart distinto) o al completar el backlog.
     */
    private RoutePlanningSupport.PlanningFlightIndex flightIndexFor(LocalDateTime runStartedAt,
                                                                    LocalDateTime minRegistration,
                                                                    LocalDateTime maxRegistration) {
        boolean sameRun = runStartedAt != null && runStartedAt.equals(cachedRunStartedAt);
        boolean covered = cachedFlightIndex != null
                && cachedIndexFrom != null
                && cachedIndexTo != null
                && !minRegistration.isBefore(cachedIndexFrom)
                && !maxRegistration.plusDays(3).isAfter(cachedIndexTo);
        if (sameRun && covered) {
            return cachedFlightIndex;
        }
        LocalDateTime from = minRegistration;
        LocalDateTime to = maxRegistration.plusDays(FLIGHT_INDEX_SPAN_DAYS + 3L);
        List<Flight> flights = routePlannerService.availableFlightsForWindow(from, to);
        cachedFlightIndex = RoutePlanningSupport.buildPlanningFlightIndex(flights);
        cachedIndexFrom = from;
        cachedIndexTo = to;
        cachedRunStartedAt = runStartedAt;
        return cachedFlightIndex;
    }

    private void invalidateFlightIndexCache() {
        cachedFlightIndex = null;
        cachedIndexFrom = null;
        cachedIndexTo = null;
        cachedRunStartedAt = null;
        cachedAirports = null;
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
        SimulationConfig config = simulationConfigRepository.findLiveConfigOrFirst();
        return config != null
                ? config
                : simulationConfigRepository.save(SimulationConfig.builder().build());
    }
}
