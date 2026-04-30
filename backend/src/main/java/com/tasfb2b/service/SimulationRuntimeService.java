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
    private final AlgorithmProfileService algorithmProfileService;
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
            AlgorithmProfileService algorithmProfileService,
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
        this.algorithmProfileService = algorithmProfileService;
        this.routePlannerService = routePlannerService;
        this.operationalAlertService = operationalAlertService;
    }

    @Transactional(readOnly = true)
    public SimulationStateDto getState() {
        SimulationConfig config = getConfig();
        LocalDateTime simulatedNow = projectedSimulationTime(config);
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
                config.getScenarioStartAt(),
                config.getRequestedScenarioStartAt(),
                effectiveScenarioStart(config),
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
                periodTickWaitCount(),
                periodTickLastElapsedMs(),
                periodTickLastWaitAt().orElse(null),
                periodTickLastWaitHorizon().orElse(null),
                periodTickLastWaitPlannedThrough().orElse(null),
                config.getStartedAt(),
                simulatedNow,
                lastTickAt().orElse(config.getRuntimeLastTickAt()),
                LocalDateTime.now()
        );
    }

    public void markStarted() {
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.putIfAbsent(KEY_SPEED, defaultSpeed.get());
        // Always re-anchor to the configured scenario start when present.
        SimulationConfig config = getConfig();
        LocalDateTime initial = config.getScenarioStartAt();
        if (initial == null && config.getProjectedFrom() != null) {
            initial = config.getProjectedFrom().atStartOfDay();
        }
        if (initial == null) {
            initial = shipmentRepository.findMinRegistrationDate();
        }
        if (initial != null) {
            runtime.put(KEY_SIM_TIME, initial);
            config.setEffectiveScenarioStartAt(initial);
            configRepository.save(config);
        }
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(null), now);
    }

    public void markResumed() {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(null), now);
    }

    public void markPaused() {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.TRUE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(null), now);
    }

    public void markStopped() {
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(null), now);
    }

    public boolean isResetting() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_RESETTING, Boolean.FALSE));
    }

    public boolean isBootstrapping() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_BOOTSTRAPPING, Boolean.FALSE));
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
        SimulationConfig config = getConfig();
        config.setIsRunning(false);
        configRepository.save(config);
        markStopped();
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

    @Transactional
    public void resetDemandKeepingNetwork() {
        runtime.put(KEY_RESETTING, Boolean.TRUE);
        try {
            resetRuntimeState();

            var flights = flightRepository.findAll();
            for (Flight flight : flights) {
                flight.setStatus(FlightStatus.SCHEDULED);
                flight.setCurrentLoad(0);
                flight.setReservedLoad(0);
            }
            flightRepository.saveAll(flights);

            var airports = airportRepository.findAll();
            for (Airport airport : airports) {
                airport.setCurrentStorageLoad(0);
            }
            airportRepository.saveAll(airports);

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
            log.info("resetOperationalData: hasStops={}, hasAuditLogs={}, hasAlerts={}, hasNonPendingShipments={}",
                    hasStops, hasAuditLogs, hasAlerts, hasNonPendingShipments);
            if (!hasStops && !hasAuditLogs && !hasAlerts && !hasNonPendingShipments) {
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

            log.info("resetOperationalData: resetting all shipments to PENDING");
            int reset = shipmentRepository.resetAllToInitialState();
            log.info("resetOperationalData: DONE, reset {} shipments", reset);
        } catch (Exception ex) {
            log.error("resetOperationalData: FAILED", ex);
            throw ex;
        } finally {
            runtime.put(KEY_RESETTING, Boolean.FALSE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stopAndResetRuntimeFast() {
        resetRuntimeState();
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

    private void resetRuntimeState() {
        SimulationConfig config = getConfig();
        config.setIsRunning(false);
        config.setStartedAt(null);
        config.setPrimaryAlgorithm(com.tasfb2b.model.AlgorithmType.GENETIC);
        config.setSecondaryAlgorithm(com.tasfb2b.model.AlgorithmType.GENETIC);
        config.setEffectiveScenarioStartAt(null);
        config.setDateAdjusted(false);
        config.setDateAdjustmentReason(null);
        config.setRuntimeSimulatedNow(null);
        config.setRuntimeLastTickAt(null);
        configRepository.save(config);
        algorithmProfileService.applyForPrimary(config.getPrimaryAlgorithm());
        markStopped();
        runtime.put(KEY_SPEED, defaultSpeed.get());
        runtime.put(KEY_REPLANS, 0L);
        runtime.put(KEY_EVENTS, 0L);
        runtime.remove(KEY_SIM_TIME);
        clearBootstrapState();
    }

    private void clearBootstrapState() {
        runtime.put(KEY_BOOTSTRAPPING, Boolean.FALSE);
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
        LocalDateTime now = LocalDateTime.now();
        runtime.put(KEY_LAST_TICK, now);
        persistRuntimeSnapshot(currentSimulationTime().orElse(tickAt), now);
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

    public Optional<LocalDateTime> currentSimulationTime() {
        Object value = runtime.get(KEY_SIM_TIME);
        if (value instanceof LocalDateTime localDateTime) {
            return Optional.of(localDateTime);
        }

        SimulationConfig config = getConfig();
        LocalDateTime persisted = config.getRuntimeSimulatedNow();
        if (persisted != null) {
            runtime.put(KEY_SIM_TIME, persisted);
        }
        return Optional.ofNullable(persisted);
    }

    public LocalDateTime effectiveNow() {
        SimulationConfig config = getConfig();
        LocalDateTime simulatedNow = projectedSimulationTime(config);
        if (simulatedNow != null) {
            return simulatedNow;
        }
        return LocalDateTime.now();
    }

    public void setSimulationTime(LocalDateTime simulatedNow) {
        if (simulatedNow != null) {
            runtime.put(KEY_SIM_TIME, simulatedNow);
            persistRuntimeSnapshot(simulatedNow, lastTickAt().orElse(null));
        }
    }

    public void restoreRuntime(LocalDateTime simulatedNow, LocalDateTime lastTickAt) {
        if (simulatedNow != null) {
            runtime.put(KEY_SIM_TIME, simulatedNow);
        } else {
            runtime.remove(KEY_SIM_TIME);
        }

        if (lastTickAt != null) {
            runtime.put(KEY_LAST_TICK, lastTickAt);
        } else {
            runtime.remove(KEY_LAST_TICK);
        }

        runtime.put(KEY_PAUSED, Boolean.FALSE);
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
        SimulationConfig config = getConfig();
        LocalDateTime from = effectiveScenarioStart(config);
        LocalDateTime to = from != null && config.getSimulationDays() != null
                ? from.plusDays(Math.max(1, config.getSimulationDays()))
                : null;

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
            critical = delayedCount + criticalCount;
        } else {
            delivered = shipmentRepository.countDeliveredTotal();
            deliveredOnTime = shipmentRepository.countDeliveredOnTimeTotal();
            active = shipmentRepository.countByStatusIn(java.util.List.of(ShipmentStatus.PENDING, ShipmentStatus.IN_ROUTE));
            critical = shipmentRepository.countByStatusIn(java.util.List.of(ShipmentStatus.CRITICAL, ShipmentStatus.DELAYED));
            delayed = shipmentRepository.countByStatus(ShipmentStatus.DELAYED);
        }

        double deliveredPct = delivered == 0 ? 0.0 : (deliveredOnTime * 100.0) / delivered;
        double avgFlightLoad = flightRepository.count() == 0
                ? 0.0
                : flightRepository.averageLoadPct();
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
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
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
        return Math.max(1, currentSpeed()) * 60L;
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

    public LocalDateTime effectiveScenarioStart(SimulationConfig config) {
        if (config == null) return null;
        if (config.getEffectiveScenarioStartAt() != null) return config.getEffectiveScenarioStartAt();
        if (config.getScenarioStartAt() != null) return config.getScenarioStartAt();
        if (config.getProjectedFrom() != null) return config.getProjectedFrom().atStartOfDay();
        return null;
    }

    private LocalDateTime projectedSimulationTime(SimulationConfig config) {
        LocalDateTime base = currentSimulationTime().orElse(config.getRuntimeSimulatedNow());
        if (base == null || config == null || !Boolean.TRUE.equals(config.getIsRunning()) || isPaused()) {
            return base;
        }

        LocalDateTime lastTickAt = lastTickAt().orElse(config.getRuntimeLastTickAt());
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
        SimulationConfig config = getConfig();
        config.setRuntimeSimulatedNow(simulatedNow);
        config.setRuntimeLastTickAt(lastTickAt);
        configRepository.save(config);
    }
}
