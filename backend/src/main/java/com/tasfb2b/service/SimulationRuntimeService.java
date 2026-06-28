package com.tasfb2b.service;

import com.tasfb2b.dto.SimulationEventDto;
import com.tasfb2b.dto.SimulationKpisDto;
import com.tasfb2b.dto.SimulationStateDto;
import com.tasfb2b.dto.SimulationTimeModeDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.OperationalAlertRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de ejecución de simulación en memoria para soporte UI.
 */
@Service
public class SimulationRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(SimulationRuntimeService.class);

    private static final String KEY_PAUSED = "paused";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_REPLANS = "replans";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_LAST_TICK = "lastTick";
    private static final String KEY_SIM_TIME = "simTime";
    private static final String KEY_RESETTING = "resetting";
    private static final String KEY_BOOTSTRAPPING = "bootstrapping";
    private static final String KEY_WARMING_UP = "warmingUp";
    private static final String KEY_BOOTSTRAP_TOTAL = "bootstrapTotal";
    private static final String KEY_BOOTSTRAP_PLANNED = "bootstrapPlanned";
    private static final String KEY_BOOTSTRAP_STARTED_AT = "bootstrapStartedAt";
    private static final String KEY_BOOTSTRAP_FINISHED_AT = "bootstrapFinishedAt";
    private static final String KEY_BOOTSTRAP_MESSAGE = "bootstrapMessage";
    private static final String KEY_PERIOD_PLANNING_ACTIVE = "periodPlanningActive";
    private static final String KEY_PERIOD_PLANNING_SEED_TARGET = "periodPlanningSeedTarget";
    private static final String KEY_PERIOD_PLANNED_THROUGH = "periodPlannedThrough";
    private static final String KEY_PERIOD_PLANNING_BACKLOG = "periodPlanningBacklog";
    private static final String KEY_PERIOD_PLANNING_BATCH_COUNT = "periodPlanningBatchCount";
    private static final String KEY_PERIOD_PLANNING_LAST_BATCH_PLANNED = "periodPlanningLastBatchPlanned";
    private static final String KEY_PERIOD_PLANNING_LAST_BATCH_FAILED = "periodPlanningLastBatchFailed";
    private static final String KEY_PERIOD_PLANNING_LAST_BATCH_ELAPSED_MS = "periodPlanningLastBatchElapsedMs";
    private static final String KEY_PERIOD_PLANNING_LAST_BATCH_AT = "periodPlanningLastBatchAt";
    private static final String KEY_PERIOD_TICK_WAIT_COUNT = "periodTickWaitCount";
    private static final String KEY_PERIOD_TICK_LAST_ELAPSED_MS = "periodTickLastElapsedMs";
    private static final String KEY_PERIOD_TICK_LAST_WAIT_AT = "periodTickLastWaitAt";
    private static final String KEY_PERIOD_TICK_LAST_WAIT_HORIZON = "periodTickLastWaitHorizon";
    private static final String KEY_PERIOD_TICK_LAST_WAIT_PLANNED_THROUGH = "periodTickLastWaitPlannedThrough";

    private final SimulationConfigRepository configRepository;
    private final ShipmentRepository shipmentRepository;
    private final FlightRepository flightRepository;
    private final AirportRepository airportRepository;
    private final TravelStopRepository travelStopRepository;
    private final ShipmentAuditLogRepository shipmentAuditLogRepository;
    private final OperationalAlertRepository operationalAlertRepository;
    private final ShipmentAuditService shipmentAuditService;
    private final RoutePlannerService routePlannerService;
    private final OperationalAlertService operationalAlertService;

    private final Map<String, Object> runtime = new ConcurrentHashMap<>();
    private final AtomicInteger defaultSpeed = new AtomicInteger(1);
    private final AtomicBoolean controlTransitionInProgress = new AtomicBoolean(false);

    public SimulationRuntimeService(
            SimulationConfigRepository configRepository,
            ShipmentRepository shipmentRepository,
            FlightRepository flightRepository,
            AirportRepository airportRepository,
            TravelStopRepository travelStopRepository,
            ShipmentAuditLogRepository shipmentAuditLogRepository,
            OperationalAlertRepository operationalAlertRepository,
            ShipmentAuditService shipmentAuditService,
            RoutePlannerService routePlannerService,
            OperationalAlertService operationalAlertService
    ) {
        this.configRepository = configRepository;
        this.shipmentRepository = shipmentRepository;
        this.flightRepository = flightRepository;
        this.airportRepository = airportRepository;
        this.travelStopRepository = travelStopRepository;
        this.shipmentAuditLogRepository = shipmentAuditLogRepository;
        this.operationalAlertRepository = operationalAlertRepository;
        this.shipmentAuditService = shipmentAuditService;
        this.routePlannerService = routePlannerService;
        this.operationalAlertService = operationalAlertService;
    }

    @Transactional(readOnly = true)
    public SimulationStateDto getState() {
        return getState(getConfig());
    }

    /** Estado del runtime indicado (live = id=1 / sim = fila PERIOD/COLLAPSE), para pantallas separadas. */
    public SimulationStateDto getState(SimulationConfig config) {
        LocalDateTime simulatedNow = projectedSimulationTime(config);
        // CLAMP del reloj de DISPLAY a la frontera de planificacion (escenarios plan-ahead). El reloj REAL
        // nunca rebasa plannedThrough (el guard del tick lo impide), pero la extrapolacion para UI fluida SI
        // se disparaba meses adelante a alta velocidad → la vista de vuelos (GET /api/flights usa este
        // simulatedNow) consultaba un dia AUN NO MATERIALIZADO y devolvia vacio ("desaparecian todos los
        // vuelos al cambiar de dia"). Solo afecta el valor MOSTRADO; no toca el reloj del motor/planificador.
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION
                || config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST) {
            LocalDateTime plannedThrough = periodPlannedThrough().orElse(null);
            if (plannedThrough == null) {
                simulatedNow = config.getRuntimeSimulatedNow() != null
                        ? config.getRuntimeSimulatedNow()
                        : effectiveScenarioStart(config);
            } else if (simulatedNow != null && simulatedNow.isAfter(plannedThrough)) {
                simulatedNow = plannedThrough;
            }
        }
        simulatedNow = displaySimulationTime(config, simulatedNow);
        LocalDateTime collapseAt = config.getCollapseDetectedAtSim();
        LocalDateTime collapseStart = effectiveScenarioStart(config);
        Long collapseSurvivalSeconds = (collapseAt != null && collapseStart != null)
                ? Math.max(0L, java.time.Duration.between(collapseStart, collapseAt).getSeconds())
                : null;
        return new SimulationStateDto(
                config.getId(),
                config.getScenario(),
                valueOr(config.getSimulationDays(), 5),
                valueOr(config.getExecutionMinutes(), 60),
                valueOr(config.getInitialVolumeAvg(), 8),
                valueOr(config.getInitialVolumeVariance(), 3),
                valueOr(config.getFlightFrequencyMultiplier(), 1),
                valueOr(config.getCancellationRatePct(), 5),
                valueOr(config.getIntraNodeCapacity(), 700),
                valueOr(config.getInterNodeCapacity(), 800),
                valueOr(config.getNormalThresholdPct(), 70),
                valueOr(config.getWarningThresholdPct(), 90),
                valueOr(config.getSlaWarnPct(), 90),
                valueOr(config.getSlaCritPct(), 75),
                valueOr(config.getRiskShipmentsWarnPct(), 10),
                valueOr(config.getRiskShipmentsCritPct(), 25),
                valueOr(config.getCriticalNodesWarnPct(), 10),
                valueOr(config.getCriticalNodesCritPct(), 25),
                config.getScenarioStartAt(),
                config.getRequestedScenarioStartAt(),
                effectiveScenarioStart(config),
                resolveScenarioEnd(config),
                Boolean.TRUE.equals(config.getDateAdjusted()),
                config.getDateAdjustmentReason(),
                Boolean.TRUE.equals(config.getProjectedDemandReady()),
                config.getProjectedHistoricalFrom(),
                config.getProjectedHistoricalTo(),
                config.getProjectedFrom(),
                config.getProjectedTo(),
                config.getProjectedGeneratedAt(),
                config.getPrimaryAlgorithm(),
                config.getSecondaryAlgorithm(),
                Boolean.TRUE.equals(config.getIsRunning()),
                isBootstrapping(),
                isPaused(),
                currentSpeed(),
                timeMode(config),
                simulationSecondsPerTick(config),
                tickIntervalMs(config),
                planningIntervalSeconds(config),
                consumptionK(config),
                consumptionWindowSeconds(config),
                effectiveSpeed(config),
                replannings(),
                injectedEvents(),
                bootstrapTotalShipments(),
                bootstrapPlannedShipments(),
                bootstrapStartedAt().orElse(null),
                bootstrapFinishedAt().orElse(null),
                bootstrapMessage(),
                isPeriodPlanningActive(),
                periodPlanningSeedTarget().orElse(null),
                periodPlannedThrough().orElse(null),
                periodPlanningBacklog(),
                periodPlanningBatchCount(),
                periodPlanningLastBatchPlanned(),
                periodPlanningLastBatchFailed(),
                periodPlanningLastBatchElapsedMs(),
                periodPlanningLastBatchAt().orElse(null),
                routePlannerService.getLastPlanningDurationMs(),
                periodTickWaitCount(),
                periodTickLastElapsedMs(),
                periodTickLastWaitAt().orElse(null),
                periodTickLastWaitHorizon().orElse(null),
                periodTickLastWaitPlannedThrough().orElse(null),
                config.getStartedAt(),
                simulatedNow,
                lastTickAt().orElse(config.getRuntimeLastTickAt()),
                LocalDateTime.now(),
                collapseAt,
                config.getCollapseShipmentCode(),
                collapseSurvivalSeconds
        );
    }

    public void markStarted() {
        markStarted(getConfig());
    }

    public void markStarted(SimulationConfig config) {
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.putIfAbsent(KEY_SPEED, defaultSpeed.get());
        // DAY_TO_DAY (operación EN VIVO): el reloj arranca en la hora real actual, no en una fecha histórica.
        // Otros escenarios: re-anclar al inicio configurado; si no, al primer envío importado.
        LocalDateTime initial;
        if (config != null && config.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY) {
            initial = LocalDateTime.now();
        } else {
            initial = config == null ? null : config.getScenarioStartAt();
            if (initial == null) {
                initial = shipmentRepository.findMinRegistrationDate();
            }
        }
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_LAST_TICK, now);
        if (config != null) {
            if (initial != null) {
                config.setEffectiveScenarioStartAt(initial);
            }
            // Reloj per-config: se escribe en la FILA del config (no en el caché global).
            persistRuntimeSnapshot(config, initial != null ? initial : config.getRuntimeSimulatedNow(), now);
        }
    }

    public void markResumed() {
        markResumed(getConfig());
    }

    public void markResumed(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(config, currentSimulationTime(config).orElse(null), now);
    }

    public void markPaused() {
        markPaused(getConfig());
    }

    public void markPaused(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.TRUE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(config, currentSimulationTime(config).orElse(null), now);
    }

    public void markStopped() {
        markStopped(getConfig());
    }

    public void markStopped(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, now);
        runtime.put(KEY_BOOTSTRAPPING, Boolean.FALSE);
        runtime.put(KEY_WARMING_UP, Boolean.FALSE);
        persistRuntimeSnapshot(config, currentSimulationTime(config).orElse(null), now);
    }

    public boolean isResetting() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_RESETTING, Boolean.FALSE));
    }

    public boolean isBootstrapping() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_BOOTSTRAPPING, Boolean.FALSE))
                || Boolean.TRUE.equals(runtime.getOrDefault(KEY_WARMING_UP, Boolean.FALSE));
    }

    public boolean isWarmingUp() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_WARMING_UP, Boolean.FALSE));
    }

    public void markWarmupStarted(long totalShipments, String message) {
        runtime.put(KEY_WARMING_UP, Boolean.TRUE);
        runtime.put(KEY_BOOTSTRAP_TOTAL, Math.max(0L, totalShipments));
        runtime.put(KEY_BOOTSTRAP_PLANNED, 0L);
        runtime.put(KEY_BOOTSTRAP_STARTED_AT, LocalDateTime.now());
        runtime.remove(KEY_BOOTSTRAP_FINISHED_AT);
        runtime.put(KEY_BOOTSTRAP_MESSAGE, message == null ? "Preparando simulacion" : message);
    }

    public void markWarmupCompleted(String message) {
        runtime.put(KEY_WARMING_UP, Boolean.FALSE);
        runtime.put(KEY_BOOTSTRAP_FINISHED_AT, LocalDateTime.now());
        runtime.put(KEY_BOOTSTRAP_MESSAGE, message == null ? "Warmup completado" : message);
    }

    public void markBootstrapStarted(long totalShipments, String message) {
        runtime.put(KEY_BOOTSTRAPPING, Boolean.TRUE);
        runtime.put(KEY_BOOTSTRAP_TOTAL, Math.max(0L, totalShipments));
        runtime.put(KEY_BOOTSTRAP_PLANNED, 0L);
        runtime.put(KEY_BOOTSTRAP_STARTED_AT, LocalDateTime.now());
        runtime.remove(KEY_BOOTSTRAP_FINISHED_AT);
        runtime.put(KEY_BOOTSTRAP_MESSAGE, message == null ? "Preparando simulacion de periodo" : message);
    }

    public void updateBootstrapProgress(long plannedShipments, String message) {
        runtime.put(KEY_BOOTSTRAP_PLANNED, Math.max(0L, plannedShipments));
        if (message != null && !message.isBlank()) {
            runtime.put(KEY_BOOTSTRAP_MESSAGE, message);
        }
    }

    public void markBootstrapCompleted(String message) {
        runtime.put(KEY_BOOTSTRAPPING, Boolean.FALSE);
        runtime.put(KEY_BOOTSTRAP_FINISHED_AT, LocalDateTime.now());
        runtime.put(KEY_BOOTSTRAP_MESSAGE, message == null ? "Bootstrap completado" : message);
    }

    public void markBootstrapFailed(String message) {
        runtime.put(KEY_BOOTSTRAPPING, Boolean.FALSE);
        runtime.put(KEY_BOOTSTRAP_FINISHED_AT, LocalDateTime.now());
        runtime.put(KEY_BOOTSTRAP_MESSAGE, message == null ? "Bootstrap fallido" : message);
    }

    public long bootstrapTotalShipments() {
        return ((Number) runtime.getOrDefault(KEY_BOOTSTRAP_TOTAL, 0L)).longValue();
    }

    public long bootstrapPlannedShipments() {
        return ((Number) runtime.getOrDefault(KEY_BOOTSTRAP_PLANNED, 0L)).longValue();
    }

    public Optional<LocalDateTime> bootstrapStartedAt() {
        Object value = runtime.get(KEY_BOOTSTRAP_STARTED_AT);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public Optional<LocalDateTime> bootstrapFinishedAt() {
        Object value = runtime.get(KEY_BOOTSTRAP_FINISHED_AT);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public String bootstrapMessage() {
        Object value = runtime.get(KEY_BOOTSTRAP_MESSAGE);
        return value instanceof String text ? text : null;
    }

    public void markPeriodPlanningActive(LocalDateTime seedTarget, long backlog, String message) {
        runtime.put(KEY_PERIOD_PLANNING_ACTIVE, Boolean.TRUE);
        if (seedTarget != null) {
            runtime.put(KEY_PERIOD_PLANNING_SEED_TARGET, seedTarget);
        } else {
            runtime.remove(KEY_PERIOD_PLANNING_SEED_TARGET);
        }
        runtime.put(KEY_PERIOD_PLANNING_BACKLOG, Math.max(0L, backlog));
        if (message != null && !message.isBlank()) {
            runtime.put(KEY_BOOTSTRAP_MESSAGE, message);
        }
    }

    public void updatePeriodPlanningProgress(LocalDateTime plannedThrough, long backlog, String message) {
        if (plannedThrough != null) {
            runtime.put(KEY_PERIOD_PLANNED_THROUGH, plannedThrough);
        }
        runtime.put(KEY_PERIOD_PLANNING_BACKLOG, Math.max(0L, backlog));
        runtime.put(KEY_PERIOD_PLANNING_ACTIVE, backlog > 0L);
        if (message != null && !message.isBlank()) {
            runtime.put(KEY_BOOTSTRAP_MESSAGE, message);
        }
        if (backlog <= 0L) {
            runtime.put(KEY_BOOTSTRAP_FINISHED_AT, LocalDateTime.now());
        }
    }

    public void recordPeriodPlanningBatch(long planned, long failed, long elapsedMs) {
        runtime.put(KEY_PERIOD_PLANNING_BATCH_COUNT, periodPlanningBatchCount() + 1L);
        runtime.put(KEY_PERIOD_PLANNING_LAST_BATCH_PLANNED, Math.max(0L, planned));
        runtime.put(KEY_PERIOD_PLANNING_LAST_BATCH_FAILED, Math.max(0L, failed));
        runtime.put(KEY_PERIOD_PLANNING_LAST_BATCH_ELAPSED_MS, Math.max(0L, elapsedMs));
        runtime.put(KEY_PERIOD_PLANNING_LAST_BATCH_AT, LocalDateTime.now());
    }

    public boolean isPeriodPlanningActive() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_PERIOD_PLANNING_ACTIVE, Boolean.FALSE));
    }

    public Optional<LocalDateTime> periodPlanningSeedTarget() {
        Object value = runtime.get(KEY_PERIOD_PLANNING_SEED_TARGET);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public Optional<LocalDateTime> periodPlannedThrough() {
        Object value = runtime.get(KEY_PERIOD_PLANNED_THROUGH);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public long periodPlanningBacklog() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_PLANNING_BACKLOG, 0L)).longValue();
    }

    public long periodPlanningBatchCount() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_PLANNING_BATCH_COUNT, 0L)).longValue();
    }

    public long periodPlanningLastBatchPlanned() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_PLANNING_LAST_BATCH_PLANNED, 0L)).longValue();
    }

    public long periodPlanningLastBatchFailed() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_PLANNING_LAST_BATCH_FAILED, 0L)).longValue();
    }

    public long periodPlanningLastBatchElapsedMs() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_PLANNING_LAST_BATCH_ELAPSED_MS, 0L)).longValue();
    }

    public Optional<LocalDateTime> periodPlanningLastBatchAt() {
        Object value = runtime.get(KEY_PERIOD_PLANNING_LAST_BATCH_AT);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public void recordPeriodTickWait(LocalDateTime horizon, LocalDateTime plannedThrough) {
        runtime.put(KEY_PERIOD_TICK_WAIT_COUNT, periodTickWaitCount() + 1L);
        runtime.put(KEY_PERIOD_TICK_LAST_WAIT_AT, LocalDateTime.now());
        if (horizon != null) {
            runtime.put(KEY_PERIOD_TICK_LAST_WAIT_HORIZON, horizon);
        }
        if (plannedThrough != null) {
            runtime.put(KEY_PERIOD_TICK_LAST_WAIT_PLANNED_THROUGH, plannedThrough);
        }
    }

    public void recordTickElapsed(long elapsedMs) {
        runtime.put(KEY_PERIOD_TICK_LAST_ELAPSED_MS, Math.max(0L, elapsedMs));
    }

    public long periodTickWaitCount() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_TICK_WAIT_COUNT, 0L)).longValue();
    }

    public long periodTickLastElapsedMs() {
        return ((Number) runtime.getOrDefault(KEY_PERIOD_TICK_LAST_ELAPSED_MS, 0L)).longValue();
    }

    public Optional<LocalDateTime> periodTickLastWaitAt() {
        Object value = runtime.get(KEY_PERIOD_TICK_LAST_WAIT_AT);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public Optional<LocalDateTime> periodTickLastWaitHorizon() {
        Object value = runtime.get(KEY_PERIOD_TICK_LAST_WAIT_HORIZON);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public Optional<LocalDateTime> periodTickLastWaitPlannedThrough() {
        Object value = runtime.get(KEY_PERIOD_TICK_LAST_WAIT_PLANNED_THROUGH);
        return value instanceof LocalDateTime localDateTime ? Optional.of(localDateTime) : Optional.empty();
    }

    public boolean beginControlTransition() {
        return controlTransitionInProgress.compareAndSet(false, true);
    }

    public void endControlTransition() {
        controlTransitionInProgress.set(false);
    }

    @Transactional
    public void stopSimulationOnly() {
        stopSimulationOnly(getConfig());
    }

    @Transactional
    public void stopSimulationOnly(SimulationConfig config) {
        if (config != null) {
            config.setIsRunning(false);
            configRepository.save(config);
        }
        markStopped(config);
    }

    public void clearPausedFlag() {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(null), now);
    }

    @Transactional
    public void resetSimulation() {
        resetDemandKeepingNetwork();
    }

    /**
     * Reset acotado a la SIMULACIÓN (HISTORICAL): limpia solo paradas/envíos HISTORICAL y los vuelos de la
     * ventana del escenario, SIN tocar la operación viva — los envíos/paradas LIVE y los vuelos de hoy quedan
     * intactos. Permite (re)iniciar una simulación sin tumbar el día a día que corre en paralelo.
     */
    @Transactional
    public void resetSimulationOperationalData(LocalDateTime windowStart, LocalDateTime windowEnd) {
        runtime.put(KEY_RESETTING, Boolean.TRUE);
        long startedAt = System.nanoTime();
        try {
            com.tasfb2b.model.ShipmentSource historical = com.tasfb2b.model.ShipmentSource.HISTORICAL;
            int deletedStops;
            int resetShipments;
            if (windowStart != null && windowEnd != null) {
                deletedStops = travelStopRepository.deleteByShipmentSourceAndRegistrationBetween(
                        historical.name(), windowStart, windowEnd);
                resetShipments = shipmentRepository.resetToInitialStateBySourceAndRegistrationBetween(
                        historical.name(), windowStart, windowEnd);
                flightRepository.resetOperationalStateInWindow(windowStart, windowEnd);
            } else {
                deletedStops = travelStopRepository.deleteByShipmentSource(historical);
                resetShipments = shipmentRepository.resetToInitialStateBySource(historical);
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("resetSimulationOperationalData: window=[{}, {}) deletedStops={} resetShipments={} elapsed={}ms",
                    windowStart, windowEnd, deletedStops, resetShipments, elapsedMs);
        } finally {
            runtime.put(KEY_RESETTING, Boolean.FALSE);
        }
    }

    @Transactional
    public void resetDemandKeepingNetwork() {
        runtime.put(KEY_RESETTING, Boolean.TRUE);
        try {
            resetRuntimeState(getConfig());

            // Reset por BULK QUERY (sin cargar entidades). Tras una corrida puede haber MILLONES de clones
            // de vuelos materializados; el viejo findAll()+saveAll los cargaba TODOS al heap y lo reventaba
            // (OutOfMemoryError). Reutilizamos los resets rapidos nativos (los mismos de resetOperationalData).
            flightRepository.resetOperationalStateFast();
            airportRepository.resetStorageLoadFast();

            clearOperationalDataWithRetry();
        } finally {
            runtime.put(KEY_RESETTING, Boolean.FALSE);
        }
    }

    /**
     * Full stop + reset in one call. For controller use, prefer calling
     * {@link #prepareStop()} → wait → {@link #resetOperationalData()} to avoid deadlocks.
     */
    @Transactional
    public void resetToInitialStateKeepingDemand() {
        prepareStop();
        resetOperationalData();
    }

    /**
     * Phase 1: stop simulation engine and set resetting flag.
     * Call this first, then wait ~1.5s for any in-progress tick to complete.
     */
    public void prepareStop() {
        runtime.put(KEY_RESETTING, Boolean.TRUE);
        stopAndResetRuntimeFast();
    }

    /**
     * Phase 2: heavy DB reset (truncates, bulk updates).
     * Only call after prepareStop() + sufficient wait for tick to finish.
     */
    @Transactional
    public void resetOperationalData() {
        log.info("resetOperationalData: STARTING heavy DB reset");
        try {
            boolean hasStops = travelStopRepository.existsByIdIsNotNull();
            boolean hasAuditLogs = shipmentAuditLogRepository.existsByIdIsNotNull();
            boolean hasAlerts = operationalAlertRepository.existsByIdIsNotNull();
            boolean hasNonPendingShipments = shipmentRepository.existsByStatusNot(ShipmentStatus.PENDING);
            // DAY_TO_DAY (operación EN VIVO): el reset borra SOLO los envíos LIVE (la operación), dejando
            // intactos los ~9.5M HISTORICAL. Los otros escenarios resetean todo a PENDING como antes.
            SimulationConfig dayToDayConfig = getConfig();
            boolean dayToDay = dayToDayConfig != null
                    && dayToDayConfig.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY;
            boolean hasLiveShipments = dayToDay && shipmentRepository.existsBySource(com.tasfb2b.model.ShipmentSource.LIVE);
            log.info("resetOperationalData: hasStops={}, hasAuditLogs={}, hasAlerts={}, hasNonPendingShipments={}, dayToDay={}, hasLive={}",
                    hasStops, hasAuditLogs, hasAlerts, hasNonPendingShipments, dayToDay, hasLiveShipments);
            if (!hasStops && !hasAuditLogs && !hasAlerts && !hasNonPendingShipments && !hasLiveShipments) {
                log.info("resetOperationalData: already clean, skipping");
                return;
            }

            log.info("resetOperationalData: resetting flights and airports");
            flightRepository.resetOperationalStateFast();
            airportRepository.resetStorageLoadFast();

            log.info("resetOperationalData: truncating travel_stop, audit_log, alerts");
            travelStopRepository.truncateFast();
            shipmentAuditLogRepository.truncateFast();
            operationalAlertRepository.truncateFast();

            if (dayToDay) {
                log.info("resetOperationalData: DAY_TO_DAY → borrando solo envíos LIVE (históricos intactos)");
                int deleted = shipmentRepository.deleteBySource(com.tasfb2b.model.ShipmentSource.LIVE);
                log.info("resetOperationalData: DONE, deleted {} envíos LIVE", deleted);
            } else {
                log.info("resetOperationalData: resetting all shipments to PENDING");
                int reset = shipmentRepository.resetAllToInitialState();
                log.info("resetOperationalData: DONE, reset {} shipments", reset);
            }
        } catch (Exception ex) {
            log.error("resetOperationalData: FAILED", ex);
            throw ex;
        } finally {
            runtime.put(KEY_RESETTING, Boolean.FALSE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stopAndResetRuntimeFast() {
        resetRuntimeState(getConfig());
    }

    private void clearOperationalDataWithRetry() {
        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                travelStopRepository.deleteAllInBatch();
                shipmentAuditLogRepository.deleteAllInBatch();
                operationalAlertRepository.deleteAllInBatch();
                shipmentRepository.deleteAllInBatch();
                return;
            } catch (DataIntegrityViolationException ex) {
                if (attempt == maxAttempts) {
                    throw ex;
                }
                shipmentAuditLogRepository.deleteAllInBatch();
                travelStopRepository.deleteAllInBatch();
            }
        }
    }

    public void resetRuntimeState(SimulationConfig config) {
        if (config == null) {
            return;
        }
        config.setIsRunning(false);
        config.setStartedAt(null);
        config.setPrimaryAlgorithm(com.tasfb2b.model.AlgorithmType.GENETIC);
        config.setSecondaryAlgorithm(com.tasfb2b.model.AlgorithmType.GENETIC);
        config.setEffectiveScenarioStartAt(null);
        config.setDateAdjusted(false);
        config.setDateAdjustmentReason(null);
        config.setCollapseDetectedAtSim(null);
        config.setCollapseShipmentId(null);
        config.setCollapseShipmentCode(null);
        config.setRuntimeSimulatedNow(null);
        config.setRuntimeLastTickAt(null);
        configRepository.save(config);
        markStopped();
        runtime.put(KEY_SPEED, defaultSpeed.get());
        runtime.put(KEY_REPLANS, 0L);
        runtime.put(KEY_EVENTS, 0L);
        runtime.remove(KEY_SIM_TIME);
        clearBootstrapState();
    }

    private void clearBootstrapState() {
        runtime.put(KEY_BOOTSTRAPPING, Boolean.FALSE);
        runtime.put(KEY_WARMING_UP, Boolean.FALSE);
        runtime.remove(KEY_BOOTSTRAP_TOTAL);
        runtime.remove(KEY_BOOTSTRAP_PLANNED);
        runtime.remove(KEY_BOOTSTRAP_STARTED_AT);
        runtime.remove(KEY_BOOTSTRAP_FINISHED_AT);
        runtime.remove(KEY_BOOTSTRAP_MESSAGE);
        runtime.remove(KEY_PERIOD_PLANNING_ACTIVE);
        runtime.remove(KEY_PERIOD_PLANNING_SEED_TARGET);
        runtime.remove(KEY_PERIOD_PLANNED_THROUGH);
        runtime.remove(KEY_PERIOD_PLANNING_BACKLOG);
        runtime.remove(KEY_PERIOD_PLANNING_BATCH_COUNT);
        runtime.remove(KEY_PERIOD_PLANNING_LAST_BATCH_PLANNED);
        runtime.remove(KEY_PERIOD_PLANNING_LAST_BATCH_FAILED);
        runtime.remove(KEY_PERIOD_PLANNING_LAST_BATCH_ELAPSED_MS);
        runtime.remove(KEY_PERIOD_PLANNING_LAST_BATCH_AT);
        runtime.remove(KEY_PERIOD_TICK_WAIT_COUNT);
        runtime.remove(KEY_PERIOD_TICK_LAST_ELAPSED_MS);
        runtime.remove(KEY_PERIOD_TICK_LAST_WAIT_AT);
        runtime.remove(KEY_PERIOD_TICK_LAST_WAIT_HORIZON);
        runtime.remove(KEY_PERIOD_TICK_LAST_WAIT_PLANNED_THROUGH);
    }

    public void markTick(LocalDateTime tickAt) {
        markTick(getConfig(), tickAt);
    }

    public void markTick(SimulationConfig config, LocalDateTime tickAt) {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_LAST_TICK, now); // marca global "motor vivo" (detección de stale)
        persistRuntimeSnapshot(config, currentSimulationTime(config).orElse(tickAt), now);
    }

    public Optional<LocalDateTime> lastTickAt() {
        Object value = runtime.get(KEY_LAST_TICK);
        if (value instanceof LocalDateTime localDateTime) {
            return Optional.of(localDateTime);
        }

        SimulationConfig config = getConfig();
        LocalDateTime persisted = config.getRuntimeLastTickAt();
        if (persisted != null) {
            runtime.put(KEY_LAST_TICK, persisted);
        }
        return Optional.ofNullable(persisted);
    }

    /** Último tick POR-CONFIG (su propia fila), independiente del marcador global de "motor vivo". */
    public Optional<LocalDateTime> lastTickAt(SimulationConfig config) {
        return Optional.ofNullable(config == null ? null : config.getRuntimeLastTickAt());
    }

    /** True si el escenario activo es la operación EN VIVO (DAY_TO_DAY) → se opera/lista solo envíos LIVE. */
    public boolean isLiveOperationScenario() {
        SimulationConfig config = getConfig();
        return config != null && config.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY;
    }

    public Optional<LocalDateTime> currentSimulationTime() {
        return currentSimulationTime(getConfig());
    }

    /**
     * Reloj POR-CONFIG: cada runtime (LIVE id=1 / SIM id=2) lleva el suyo en su propia fila
     * ({@code runtime_simulated_now}). Se lee de la fila (no de un caché único) para que dos configs
     * ticando a la vez no se pisen el reloj.
     */
    public Optional<LocalDateTime> currentSimulationTime(SimulationConfig config) {
        return Optional.ofNullable(config == null ? null : config.getRuntimeSimulatedNow());
    }

    public LocalDateTime effectiveNow() {
        return effectiveNow(getConfig());
    }

    public LocalDateTime effectiveNow(SimulationConfig config) {
        LocalDateTime simulatedNow = displaySimulationTime(config, projectedSimulationTime(config));
        if (config != null
                && (config.getScenario() == com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION
                    || config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST)) {
            LocalDateTime plannedThrough = periodPlannedThrough().orElse(null);
            if (plannedThrough == null) {
                simulatedNow = config.getRuntimeSimulatedNow() != null
                        ? config.getRuntimeSimulatedNow()
                        : effectiveScenarioStart(config);
            } else if (simulatedNow != null && simulatedNow.isAfter(plannedThrough)) {
                simulatedNow = plannedThrough;
            }
        }
        if (simulatedNow != null) {
            return simulatedNow;
        }
        return LocalDateTime.now();
    }

    public void setSimulationTime(LocalDateTime simulatedNow) {
        setSimulationTime(getConfig(), simulatedNow);
    }

    public void setSimulationTime(SimulationConfig config, LocalDateTime simulatedNow) {
        if (config != null && simulatedNow != null) {
            persistRuntimeSnapshot(config, simulatedNow, config.getRuntimeLastTickAt());
        }
    }

    public void restoreRuntime(LocalDateTime simulatedNow, LocalDateTime lastTickAt) {
        if (lastTickAt != null) {
            runtime.put(KEY_LAST_TICK, lastTickAt);
        } else {
            runtime.remove(KEY_LAST_TICK);
        }
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        // El reloj efectivo vive en la FILA del config (per-config) — persistirlo ahí, no solo en caché.
        persistRuntimeSnapshot(getConfig(), simulatedNow, lastTickAt);
    }

    public void setSpeed(int speed) {
        runtime.put(KEY_SPEED, Math.max(1, Math.min(20, speed)));
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    public int currentSpeed() {
        return (int) runtime.getOrDefault(KEY_SPEED, defaultSpeed.get());
    }

    public boolean isPaused() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_PAUSED, Boolean.FALSE));
    }

    public long replannings() {
        return ((Number) runtime.getOrDefault(KEY_REPLANS, 0L)).longValue();
    }

    public long injectedEvents() {
        return ((Number) runtime.getOrDefault(KEY_EVENTS, 0L)).longValue();
    }

    public void increaseReplanningCounter() {
        long cur = replannings();
        runtime.put(KEY_REPLANS, cur + 1);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    @Transactional
    public String injectEvent(SimulationEventDto dto) {
        String type = dto.type().trim().toUpperCase();
        runtime.put(KEY_EVENTS, injectedEvents() + 1);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());

        return switch (type) {
            case "CANCEL_FLIGHT" -> cancelFlight(dto.flightId());
            case "INCREASE_VOLUME" -> increaseVolume(dto.eventValue());
            case "FLAG_SHIPMENT_CRITICAL" -> markShipmentCritical(dto.shipmentId(), dto.note());
            default -> "Evento registrado sin accion automatica: " + type;
        };
    }

    @Transactional(readOnly = true)
    public SimulationKpisDto computeKpis() {
        return computeKpis(getConfig());
    }

    @Transactional(readOnly = true)
    public SimulationKpisDto computeKpis(SimulationConfig config) {
        LocalDateTime from = effectiveScenarioStart(config);
        LocalDateTime to = from != null && config.getSimulationDays() != null
                ? from.plusDays(Math.max(1, config.getSimulationDays()))
                : null;
        if (config != null && config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST) {
            to = config.getRuntimeSimulatedNow() != null ? config.getRuntimeSimulatedNow() : resolveScenarioEnd(config);
        }

        long delivered;
        long deliveredOnTime;
        long active;
        long critical;
        long delayed;

        if (from != null && to != null) {
            delivered = shipmentRepository.countDeliveredBetween(from, to);
            deliveredOnTime = shipmentRepository.countDeliveredOnTimeBetween(from, to);
            long pending = shipmentRepository.countByStatusAndRegistrationDateBetween(ShipmentStatus.PENDING, from, to);
            long inRoute = shipmentRepository.countByStatusAndRegistrationDateBetween(ShipmentStatus.IN_ROUTE, from, to);
            long delayedCount = shipmentRepository.countByStatusAndRegistrationDateBetween(ShipmentStatus.DELAYED, from, to);
            long criticalCount = shipmentRepository.countByStatusAndRegistrationDateBetween(ShipmentStatus.CRITICAL, from, to);
            active = pending + inRoute;
            delayed = delayedCount;
            critical = criticalCount;
        } else {
            delivered = shipmentRepository.countDeliveredTotal();
            deliveredOnTime = shipmentRepository.countDeliveredOnTimeTotal();
            active = shipmentRepository.countByStatusIn(java.util.List.of(ShipmentStatus.PENDING, ShipmentStatus.IN_ROUTE));
            critical = shipmentRepository.countByStatus(ShipmentStatus.CRITICAL);
            delayed = shipmentRepository.countByStatus(ShipmentStatus.DELAYED);
        }

        double deliveredPct = delivered == 0 ? 0.0 : (deliveredOnTime * 100.0) / delivered;
        double avgFlightLoad = (from != null && to != null)
                ? flightRepository.averageOccupiedLoadPctBetween(from, to)
                : flightRepository.averageOccupiedLoadPct();
        double avgNodeLoad = airportRepository.count() == 0
                ? 0.0
                : airportRepository.averageOccupancyPct();

        return new SimulationKpisDto(
                deliveredPct,
                avgFlightLoad,
                avgNodeLoad,
                replannings(),
                delivered,
                delayed,
                active,
                critical,
                injectedEvents()
        );
    }

    @Transactional
    public void markShipmentDelivered(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(effectiveNow());
        shipment.setProgressPercentage(100.0);
        shipmentRepository.save(shipment);
    }

    private String cancelFlight(Long flightId) {
        if (flightId == null) {
            throw new IllegalArgumentException("flightId es obligatorio para CANCEL_FLIGHT");
        }

        Flight flight = flightRepository.findById(flightId)
                .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado: " + flightId));
        if (flight.getStatus() == FlightStatus.COMPLETED) {
            return "No se puede cancelar un vuelo completado: " + flight.getFlightCode();
        }

        flight.setStatus(FlightStatus.CANCELLED);
        flightRepository.save(flight);

        java.util.Set<Long> affectedShipmentIds = travelStopRepository.findByFlightAndStopStatus(flight, StopStatus.PENDING)
                .stream()
                .map(TravelStop::getShipment)
                .filter(java.util.Objects::nonNull)
                .map(Shipment::getId)
                .collect(java.util.stream.Collectors.toSet());

        int replanned = 0;
        int failed = 0;
        for (Long shipmentId : affectedShipmentIds) {
            try {
                routePlannerService.replanShipment(shipmentId);
                replanned++;
            } catch (Exception ex) {
                failed++;
            }
        }

        Shipment auditTarget = affectedShipmentIds.isEmpty()
                ? null
                : shipmentRepository.findById(affectedShipmentIds.iterator().next()).orElse(null);
        if (auditTarget != null) {
            shipmentAuditService.log(
                    auditTarget,
                    ShipmentAuditType.EVENT_INJECTED,
                    "Evento manual: vuelo " + flight.getFlightCode() + " cancelado · replanificados=" + replanned + " · fallidos=" + failed,
                    flight.getOriginAirport(),
                    flight.getFlightCode()
            );
            operationalAlertService.ensureShipmentAlert(auditTarget, "FLIGHT_CANCELLED", "Vuelo cancelado: " + flight.getFlightCode());
        }

        increaseReplanningCounter();
        return "Vuelo " + flight.getFlightCode() + " cancelado · afectados=" + affectedShipmentIds.size() + " · replanificados=" + replanned + " · fallidos=" + failed;
    }

    private String increaseVolume(Integer eventValue) {
        int value = eventValue == null ? 10 : eventValue;
        return "Incremento de volumen inyectado en +" + value + " unidades";
    }

    private String markShipmentCritical(Long shipmentId, String note) {
        if (shipmentId == null) {
            throw new IllegalArgumentException("shipmentId es obligatorio para FLAG_SHIPMENT_CRITICAL");
        }

        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));
        shipment.setStatus(ShipmentStatus.CRITICAL);
        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.CRITICAL,
                "Evento manual: envio marcado como critico"
                        + (note == null || note.isBlank() ? "" : " - " + note.trim()),
                shipment.getOriginAirport(),
                null
        );
        operationalAlertService.ensureShipmentAlert(shipment, "MANUAL_CRITICAL", "El envío fue marcado manualmente como crítico");

        return "Envío " + shipment.getShipmentCode() + " marcado en CRITICAL"
                + (note == null || note.isBlank() ? "" : " - " + note.trim());
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findLiveConfigOrFirst();
        return config != null
                ? config
                : configRepository.save(SimulationConfig.builder().build());
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    public long simulationSecondsPerTick(SimulationConfig config) {
        if (config == null || config.getScenario() == null || config.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY) {
            return 1L;
        }
        long requestedSeconds = Math.max(1, currentSpeed()) * 60L;
        return Math.min(requestedSeconds, consumptionWindowSeconds(config));
    }

    public long tickIntervalMs(SimulationConfig config) {
        if (config == null || config.getScenario() == null || config.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY) {
            return 1_000L;
        }
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION) {
            return 500L;
        }
        return 1_000L;
    }

    public SimulationTimeModeDto timeMode(SimulationConfig config) {
        return simulationSecondsPerTick(config) == 1L
                ? SimulationTimeModeDto.REAL_TIME
                : SimulationTimeModeDto.ACCELERATED;
    }

    public long effectiveSpeed(SimulationConfig config) {
        return simulationSecondsPerTick(config);
    }

    public long planningIntervalSeconds(SimulationConfig config) {
        if (config == null || config.getScenario() == null) {
            return 300L;
        }
        return switch (config.getScenario()) {
            case DAY_TO_DAY -> 300L;
            case PERIOD_SIMULATION, COLLAPSE_TEST -> 300L;
        };
    }

    public long consumptionK(SimulationConfig config) {
        if (config == null || config.getScenario() == null) {
            return 1L;
        }
        return switch (config.getScenario()) {
            case DAY_TO_DAY -> 1L;
            case PERIOD_SIMULATION -> 14L;
            case COLLAPSE_TEST -> 75L;
        };
    }

    public long consumptionWindowSeconds(SimulationConfig config) {
        return Math.max(1L, planningIntervalSeconds(config)) * Math.max(1L, consumptionK(config));
    }

    public LocalDateTime effectiveScenarioStart(SimulationConfig config) {
        if (config == null) return null;
        if (config.getEffectiveScenarioStartAt() != null) return config.getEffectiveScenarioStartAt();
        if (config.getScenarioStartAt() != null) return config.getScenarioStartAt();
        if (config.getProjectedFrom() != null) return config.getProjectedFrom().atStartOfDay();
        return null;
    }

    /**
     * Fin de la ventana del escenario (único punto de verdad para motor, planner y bootstrap).
     * Todos los escenarios usan la MISMA planificación "por adelantado"; solo cambia el parámetro de fin:
     * <ul>
     *   <li>PERIOD_SIMULATION: inicio + simulationDays.</li>
     *   <li>COLLAPSE_TEST: hasta el último envío importado (fin de datos); el colapso lo detiene antes.</li>
     *   <li>DAY_TO_DAY: null (operación continua / live, sin fin fijo).</li>
     * </ul>
     */
    public LocalDateTime resolveScenarioEnd(SimulationConfig config) {
        if (config == null || config.getScenario() == null) {
            return null;
        }
        return switch (config.getScenario()) {
            case PERIOD_SIMULATION -> {
                LocalDateTime start = effectiveScenarioStart(config);
                if (start == null) yield null;
                int days = config.getSimulationDays() == null ? 5 : Math.max(1, config.getSimulationDays());
                yield start.plusDays(days);
            }
            case COLLAPSE_TEST -> {
                LocalDateTime maxReg = shipmentRepository.findMaxRegistrationDate();
                yield maxReg == null ? null : maxReg.plusDays(3);
            }
            default -> null;
        };
    }

    public LocalDateTime displaySimulationTime(SimulationConfig config, LocalDateTime candidate) {
        if (config == null || candidate == null || config.getScenario() == null) {
            return candidate;
        }
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST
                && config.getCollapseDetectedAtSim() != null
                && candidate.isAfter(config.getCollapseDetectedAtSim())) {
            return config.getCollapseDetectedAtSim();
        }
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION) {
            LocalDateTime periodEnd = resolveScenarioEnd(config);
            if (periodEnd != null && candidate.isAfter(periodEnd)) {
                return periodEnd;
            }
        }
        return candidate;
    }

    private LocalDateTime projectedSimulationTime(SimulationConfig config) {
        LocalDateTime base = currentSimulationTime(config).orElse(config == null ? null : config.getRuntimeSimulatedNow());
        if (base == null || config == null || !Boolean.TRUE.equals(config.getIsRunning()) || isPaused()) {
            return base;
        }

        LocalDateTime lastTickAt = lastTickAt(config).orElse(config.getRuntimeLastTickAt());
        if (lastTickAt == null) {
            return base;
        }

        long elapsedMillis = Math.max(0L, java.time.Duration.between(lastTickAt, LocalDateTime.now()).toMillis());
        if (elapsedMillis <= 0L) {
            return base;
        }

        double simSecondsPerRealSecond = (double) simulationSecondsPerTick(config) * 1_000d / (double) tickIntervalMs(config);
        long projectedSeconds = (long) Math.floor((elapsedMillis / 1_000d) * simSecondsPerRealSecond);
        if (projectedSeconds <= 0L) {
            return base;
        }
        return base.plusSeconds(projectedSeconds);
    }

    private void persistRuntimeSnapshot(LocalDateTime simulatedNow, LocalDateTime lastTickAt) {
        persistRuntimeSnapshot(getConfig(), simulatedNow, lastTickAt);
    }

    private void persistRuntimeSnapshot(SimulationConfig config, LocalDateTime simulatedNow, LocalDateTime lastTickAt) {
        if (config == null) {
            return;
        }
        config.setRuntimeSimulatedNow(simulatedNow);
        config.setRuntimeLastTickAt(lastTickAt);
        configRepository.save(config);
    }
}
