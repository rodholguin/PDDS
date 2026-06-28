package com.tasfb2b.controller;

import com.tasfb2b.dto.CollapseRiskDto;
import com.tasfb2b.dto.SimulationConfigUpdateDto;
import com.tasfb2b.dto.SimulationEventDto;
import com.tasfb2b.dto.SimulationResultsDto;
import com.tasfb2b.dto.SimulationSpeedDto;
import com.tasfb2b.dto.SimulationStateDto;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.OperationalBootstrapService;
import com.tasfb2b.service.FlightScheduleService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.PeriodSimulationBootstrapService;
import com.tasfb2b.service.WarmupService;
import com.tasfb2b.service.SimulationAsyncOperationsService;
import com.tasfb2b.service.SimulationEngineService;
import com.tasfb2b.service.SimulationExportService;
import com.tasfb2b.service.SimulationRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/simulation")
@Tag(name = "Simulación", description = "Control del escenario de simulación y métricas de colapso")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationConfigRepository configRepository;
    private final RoutePlannerService routePlannerService;
    private final CollapseMonitorService collapseMonitorService;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final SimulationRuntimeService runtimeService;
    private final OperationalBootstrapService operationalBootstrapService;
    private final SimulationExportService simulationExportService;
    private final SimulationEngineService simulationEngineService;
    private final SimulationAsyncOperationsService simulationAsyncOperationsService;
    private final FlightScheduleService flightScheduleService;
    private final PeriodSimulationBootstrapService periodSimulationBootstrapService;
    private final WarmupService warmupService;

    public SimulationController(
            SimulationConfigRepository configRepository,
            RoutePlannerService routePlannerService,
            CollapseMonitorService collapseMonitorService,
            ShipmentRepository shipmentRepository,
            TravelStopRepository travelStopRepository,
            SimulationRuntimeService runtimeService,
            OperationalBootstrapService operationalBootstrapService,
            SimulationExportService simulationExportService,
            SimulationEngineService simulationEngineService,
            SimulationAsyncOperationsService simulationAsyncOperationsService,
            FlightScheduleService flightScheduleService,
            PeriodSimulationBootstrapService periodSimulationBootstrapService,
            WarmupService warmupService
    ) {
        this.configRepository = configRepository;
        this.routePlannerService = routePlannerService;
        this.collapseMonitorService = collapseMonitorService;
        this.shipmentRepository = shipmentRepository;
        this.travelStopRepository = travelStopRepository;
        this.runtimeService = runtimeService;
        this.operationalBootstrapService = operationalBootstrapService;
        this.simulationExportService = simulationExportService;
        this.simulationEngineService = simulationEngineService;
        this.simulationAsyncOperationsService = simulationAsyncOperationsService;
        this.flightScheduleService = flightScheduleService;
        this.periodSimulationBootstrapService = periodSimulationBootstrapService;
        this.warmupService = warmupService;
    }

    @GetMapping("/state")
    @Operation(summary = "Obtener estado actual de la simulación")
    public ResponseEntity<SimulationStateDto> getState(
            @RequestParam(required = false, defaultValue = "live") String mode) {
        if ("sim".equalsIgnoreCase(mode)) {
            SimulationConfig sim = configRepository.findFirstByScenarioInAndIsRunningTrue(java.util.List.of(
                    com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION,
                    com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST)).orElse(null);
            if (sim == null) {
                sim = configRepository.findFirstByScenarioIn(java.util.List.of(
                        com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION,
                        com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST)).orElse(null);
            }
            if (sim != null) {
                return ResponseEntity.ok(runtimeService.getState(sim));
            }
        }
        return ResponseEntity.ok(runtimeService.getState());
    }

    @GetMapping("/sim-state")
    @Operation(summary = "Estado liviano de la SIMULACIÓN (corre en paralelo a la operación viva)")
    public ResponseEntity<Map<String, Object>> simState() {
        SimulationConfig sim = configRepository.findFirstByScenarioInAndIsRunningTrue(java.util.List.of(
                com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION,
                com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST)).orElse(null);
        if (sim == null) {
            sim = configRepository.findFirstByScenarioIn(java.util.List.of(
                    com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION,
                    com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST)).orElse(null);
        }
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("exists", sim != null);
        if (sim != null) {
            m.put("scenario", sim.getScenario());
            m.put("running", sim.getIsRunning());
            m.put("simulatedNow", sim.getRuntimeSimulatedNow());
            m.put("scenarioStartAt", sim.getEffectiveScenarioStartAt());
            m.put("simulationDays", sim.getSimulationDays());
            m.put("collapseDetectedAtSim", sim.getCollapseDetectedAtSim());
            m.put("collapseShipmentCode", sim.getCollapseShipmentCode());
        }
        return ResponseEntity.ok(m);
    }

    @PostMapping("/configure")
    @Operation(summary = "Actualizar parametros de simulación")
    public ResponseEntity<?> configure(@Valid @RequestBody SimulationConfigUpdateDto dto) {
        SimulationConfig config = getSimConfig();

        if (Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Deten la simulacion antes de reconfigurar"
            ));
        }

        if (dto.scenario() != null) config.setScenario(dto.scenario());
        if (dto.simulationDays() != null) config.setSimulationDays(dto.simulationDays());
        if (dto.executionMinutes() != null) config.setExecutionMinutes(dto.executionMinutes());
        if (dto.initialVolumeAvg() != null) config.setInitialVolumeAvg(dto.initialVolumeAvg());
        if (dto.initialVolumeVariance() != null) config.setInitialVolumeVariance(dto.initialVolumeVariance());
        if (dto.flightFrequencyMultiplier() != null) config.setFlightFrequencyMultiplier(dto.flightFrequencyMultiplier());
        if (dto.cancellationRatePct() != null) config.setCancellationRatePct(dto.cancellationRatePct());
        if (dto.intraNodeCapacity() != null) config.setIntraNodeCapacity(dto.intraNodeCapacity());
        if (dto.interNodeCapacity() != null) config.setInterNodeCapacity(dto.interNodeCapacity());
        if (dto.normalThresholdPct() != null) config.setNormalThresholdPct(dto.normalThresholdPct());
        if (dto.warningThresholdPct() != null) config.setWarningThresholdPct(dto.warningThresholdPct());
        if (dto.slaWarnPct() != null) config.setSlaWarnPct(dto.slaWarnPct());
        if (dto.slaCritPct() != null) config.setSlaCritPct(dto.slaCritPct());
        if (dto.riskShipmentsWarnPct() != null) config.setRiskShipmentsWarnPct(dto.riskShipmentsWarnPct());
        if (dto.riskShipmentsCritPct() != null) config.setRiskShipmentsCritPct(dto.riskShipmentsCritPct());
        if (dto.criticalNodesWarnPct() != null) config.setCriticalNodesWarnPct(dto.criticalNodesWarnPct());
        if (dto.criticalNodesCritPct() != null) config.setCriticalNodesCritPct(dto.criticalNodesCritPct());
        if (dto.scenarioStartDate() != null) {
            LocalDateTime scenarioStart = dto.scenarioStartDate();
            config.setRequestedScenarioStartAt(scenarioStart);
            config.setScenarioStartAt(scenarioStart);
            config.setEffectiveScenarioStartAt(scenarioStart);
            config.setDateAdjusted(false);
            config.setDateAdjustmentReason(null);
        }
        if (dto.primaryAlgorithm() != null) config.setPrimaryAlgorithm(dto.primaryAlgorithm());
        if (dto.secondaryAlgorithm() != null) config.setSecondaryAlgorithm(dto.secondaryAlgorithm());

        configRepository.save(config);
        return ResponseEntity.ok(runtimeService.getState(config));
    }

    @PostMapping("/start")
    @Operation(summary = "Iniciar simulación")
    public ResponseEntity<?> start() {
        SimulationConfig config = getSimConfig();
        if (Boolean.TRUE.equals(config.getIsRunning()) && runtimeService.isPaused()) {
            runtimeService.markResumed(config);
            return ResponseEntity.ok(Map.of(
                    "message", "Simulacion reanudada",
                    "state", runtimeService.getState(config)
            ));
        }
        if (Boolean.TRUE.equals(config.getIsRunning()) && !runtimeService.isPaused()) {
            return ResponseEntity.ok(Map.of(
                    "message", "La simulacion ya estaba en curso",
                    "state", runtimeService.getState(config)
            ));
        }

        if (!runtimeService.beginControlTransition()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Ya hay una transicion de control en curso"
            ));
        }

        runtimeService.markBootstrapStarted(0L, "Preparando simulacion");
        Long configId = config.getId();
        runControlAsync("simulation-start-" + configId, () -> {
            try {
                SimulationConfig fresh = configRepository.findById(configId)
                        .orElseThrow(() -> new IllegalStateException("Config de simulacion no encontrada: " + configId));
                log.info("Inicio async de simulacion: configId={}", configId);
                startSimulationRun(fresh);
                log.info("Fin async de simulacion: configId={}", configId);
            } catch (Throwable ex) {
                log.error("No se pudo iniciar la simulacion", ex);
                runtimeService.markBootstrapFailed("No se pudo iniciar la simulacion: " + ex.getMessage());
                configRepository.findById(configId).ifPresent(failed -> {
                    failed.setIsRunning(false);
                    configRepository.save(failed);
                });
            } finally {
                runtimeService.endControlTransition();
            }
        });

        return ResponseEntity.ok(Map.of(
                "message", "Preparando simulacion",
                "state", runtimeService.getState(config)
        ));
    }

    private void startSimulationRun(SimulationConfig config) {
        // Issue 7.2: resolve scenario/date mutations BEFORE flipping isRunning so that concurrent
        // ticks never observe running=true with stale scenarioStartAt/effectiveScenarioStartAt.
        config.setStartedAt(LocalDateTime.now());
        // Reiniciar la marca de colapso para empezar una nueva corrida limpia.
        config.setCollapseDetectedAtSim(null);
        config.setCollapseShipmentId(null);
        config.setCollapseShipmentCode(null);

        // El reset (acotado a HISTORICAL, sin tocar la operación viva) se hace abajo una vez resuelta la
        // fecha de inicio, para conocer la ventana de vuelos del sim a resetear.

        // Nota: las capacidades (vuelos y nodos) son fijas del dataset; NO se sobrescriben.
        // El almacén de nodos además es cosmético para el colapso por deadline (el ruteo sólo
        // respeta la capacidad de VUELOS). La palanca legítima para retrasar el colapso es el ruteo.
        LocalDateTime minDemand = shipmentRepository.findMinRegistrationDate();
        LocalDateTime maxDemand = shipmentRepository.findMaxRegistrationDate();
        LocalDateTime desiredStart = config.getScenarioStartAt();
        config.setRequestedScenarioStartAt(desiredStart);

        // Sin fecha explícita, la simulación arranca en el día del primer envío importado.
        if (desiredStart == null && minDemand != null) {
            desiredStart = minDemand;
            config.setScenarioStartAt(desiredStart);
            config.setEffectiveScenarioStartAt(desiredStart);
            config.setDateAdjusted(false);
            config.setDateAdjustmentReason("Fecha no enviada; se usa el primer día de envíos importados");
            log.info("scenarioStartAt por defecto al primer envío: {}", desiredStart);
        }

        boolean dateAdjusted = false;
        String adjustmentReason = null;
        if (minDemand != null) {
            if (desiredStart == null || desiredStart.isBefore(minDemand)
                    || (maxDemand != null && desiredStart.isAfter(maxDemand))) {
                // Ajustar al rango disponible de envíos importados (primer envío).
                LocalDateTime fallback = minDemand;
                log.warn("scenarioStartAt ajustado: solicitado={}, rango=[{}, {}], usando={}",
                        desiredStart, minDemand, maxDemand, fallback);
                desiredStart = fallback;
                config.setScenarioStartAt(desiredStart);
                dateAdjusted = true;
                adjustmentReason = "Fecha ajustada al rango de envíos disponibles";
            }
        }
        config.setEffectiveScenarioStartAt(desiredStart);
        config.setDateAdjusted(dateAdjusted);
        config.setDateAdjustmentReason(adjustmentReason);
        // Reset ACOTADO a la simulación (HISTORICAL + vuelos de su ventana). La operación viva (envíos/paradas
        // LIVE y vuelos de hoy) NO se toca → el día a día sigue corriendo en paralelo sin interrupción.
        runtimeService.resetSimulationOperationalData(desiredStart, runtimeService.resolveScenarioEnd(config));
        if (desiredStart != null) {
            runtimeService.setSimulationTime(config, desiredStart);
            if (warmupService.requiresWarmup(config, desiredStart)) {
                var warmup = warmupService.runWarmup(config, desiredStart);
                log.info("Warmup PERIOD_SIMULATION completado: total={} planned={} ticks={} from={} to={}",
                        warmup.totalShipments(), warmup.plannedShipments(), warmup.ticksExecuted(),
                        warmup.warmupFrom(), warmup.warmupTo());
            }
            flightScheduleService.ensureFlightsForSimulationWindow(desiredStart);
            if (periodSimulationBootstrapService.requiresBootstrap(config)) {
                var bootstrap = periodSimulationBootstrapService.seedPeriod(config, desiredStart);
                log.info("Bootstrap PERIOD_SIMULATION semilla completado: total={}, planned={}, failed={}, seedTarget={}, plannedThrough={}",
                        bootstrap.totalShipments(), bootstrap.plannedShipments(), bootstrap.failedShipments(), bootstrap.seedTarget(), bootstrap.plannedThrough());
            }
        }

        simulationEngineService.resetTickSequence();

        // Flip isRunning last — after all scenario/date fields are finalized in memory —
        // so the single save below atomically publishes a consistent state to the DB.
        config.setIsRunning(true);
        configRepository.save(config);

        log.info("Simulacion iniciada — escenario={}, secondsPerTick={}, scenarioStartAt={}, effectiveStartAt={}, dateAdjusted={}",
                config.getScenario(),
                runtimeService.simulationSecondsPerTick(config),
                config.getScenarioStartAt(),
                desiredStart,
                dateAdjusted);

        runtimeService.markStarted(config);

    }

    @PostMapping("/stop")
    @Operation(summary = "Detener simulación y reiniciar estado operativo")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> stop() {
        SimulationConfig sim = getSimConfig();
        if (!runtimeService.beginControlTransition()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Ya hay una transicion de control en curso",
                    "state", runtimeService.getState(sim)
            ));
        }

        runtimeService.markBootstrapStarted(0L, "Deteniendo simulacion y limpiando estado operativo");
        Long configId = sim.getId();
        runControlAsync("simulation-stop-" + configId, () -> {
            try {
                SimulationConfig fresh = configRepository.findById(configId)
                        .orElseThrow(() -> new IllegalStateException("Config de simulacion no encontrada: " + configId));
                log.info("Detencion async de simulacion: configId={}", configId);
                stopSimulationRun(fresh);
            } catch (Throwable ex) {
                log.error("No se pudo detener la simulacion", ex);
                runtimeService.markBootstrapFailed("No se pudo detener la simulacion: " + ex.getMessage());
            } finally {
                runtimeService.endControlTransition();
            }
        });

        return ResponseEntity.ok(Map.of(
                "message", "Deteniendo simulacion",
                "state", runtimeService.getState(sim)
        ));
    }

    private void stopSimulationRun(SimulationConfig sim) {
        runtimeService.stopSimulationOnly(sim);
        runtimeService.markBootstrapStarted(0L, "Esperando que termine el tick en curso");
        if (!awaitTickDrain()) {
            runtimeService.markBootstrapFailed("No se pudo detener: aun hay un tick largo en progreso");
            return;
        }
        simulationEngineService.resetTickSequence();
        runtimeService.markBootstrapStarted(0L, "Limpiando datos operativos de la simulacion");
        runtimeService.resetSimulationOperationalData(sim.getEffectiveScenarioStartAt(), runtimeService.resolveScenarioEnd(sim));
        runtimeService.resetRuntimeState(sim);
        runtimeService.markBootstrapCompleted("Simulacion detenida y estado reiniciado");
    }

    private void runControlAsync(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(false);
        thread.start();
    }

    @PostMapping("/reset-to-initial")
    @Operation(summary = "Reiniciar simulacion a estado inicial manteniendo pedidos")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> resetToInitial() {
        if (!runtimeService.beginControlTransition()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Ya hay una transicion de control en curso"
            ));
        }
        try {
            SimulationConfig config = getSimConfig();
            runtimeService.prepareStop();
            if (!awaitTickDrain()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "No se pudo reiniciar porque aun hay un tick largo en progreso"
                ));
            }
            simulationEngineService.resetTickSequence();
            runtimeService.resetOperationalData();
            return ResponseEntity.ok(Map.of(
                    "message", "Estado inicial restaurado",
                    "state", runtimeService.getState(config)
            ));
        } finally {
            runtimeService.endControlTransition();
        }
    }

    @PostMapping("/reset-demand")
    @Operation(summary = "Borrar todos los envíos y reiniciar operación")
    public ResponseEntity<?> resetDemand() {
        if (!runtimeService.beginControlTransition()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Ya hay una transicion de control en curso"
            ));
        }
        try {
            runtimeService.resetDemandKeepingNetwork();
            return ResponseEntity.ok(Map.of(
                    "message", "Demanda limpiada y simulacion reiniciada",
                    "state", runtimeService.getState()
            ));
        } finally {
            runtimeService.endControlTransition();
        }
    }

    @PostMapping("/pause")
    @Operation(summary = "Pausar simulación")
    public ResponseEntity<?> pause() {
        SimulationConfig config = getSimConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No hay simulacion en ejecucion"));
        }
        if (runtimeService.isPaused()) {
            return ResponseEntity.ok(Map.of(
                    "message", "La simulacion ya estaba pausada",
                    "state", runtimeService.getState(config)
            ));
        }

        runtimeService.markPaused(config);
        awaitTickDrain();
        return ResponseEntity.ok(Map.of(
                "message", "Simulacion pausada",
                "state", runtimeService.getState(config)
        ));
    }

    @PostMapping("/resume")
    @Operation(summary = "Reanudar simulación pausada")
    public ResponseEntity<?> resume() {
        SimulationConfig config = getSimConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No hay simulacion iniciada"));
        }
        if (!runtimeService.isPaused()) {
            return ResponseEntity.ok(Map.of(
                    "message", "La simulacion ya estaba corriendo",
                    "state", runtimeService.getState(config)
            ));
        }

        runtimeService.markResumed(config);
        return ResponseEntity.ok(Map.of(
                "message", "Simulacion reanudada",
                "state", runtimeService.getState(config)
        ));
    }

    @PostMapping("/speed")
    @Operation(summary = "Cambiar velocidad de simulación")
    public ResponseEntity<?> setSpeed(@Valid @RequestBody SimulationSpeedDto dto) {
        SimulationConfig config = getSimConfig();
        runtimeService.setSpeed(dto.speed());
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("message", "Velocidad actualizada");
        response.put("speed", runtimeService.currentSpeed());
        response.put("state", runtimeService.getState(config));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/events")
    @Operation(summary = "Inyectar evento manual en simulación")
    public ResponseEntity<?> injectEvent(@Valid @RequestBody SimulationEventDto dto) {
        SimulationConfig config = getSimConfig();
        String result = runtimeService.injectEvent(dto);
        return ResponseEntity.ok(Map.of(
                "message", result,
                "state", runtimeService.getState(config)
        ));
    }

    @PostMapping("/deliver/{shipmentId}")
    @Operation(summary = "Confirmar entrega manual de un envío")
    public ResponseEntity<?> deliver(@PathVariable Long shipmentId) {
        return ResponseEntity.ok(routePlannerService.markDelivered(shipmentId));
    }

    @GetMapping("/collapse-risk")
    @Operation(summary = "Riesgo de colapso del sistema")
    public ResponseEntity<CollapseRiskDto> getCollapseRisk() {
        var snapshot = collapseMonitorService.snapshot();
        double risk = routePlannerService.getCollapseRisk();
        double sysLoad = snapshot.avgLoadPct();
        double hours = snapshot.hoursToCollapse();
        var bottlenecks = snapshot.bottlenecks()
                .stream()
                .map(airport -> airport.getIcaoCode())
                .toList();

        return ResponseEntity.ok(new CollapseRiskDto(
                risk,
                bottlenecks,
                hours == Double.MAX_VALUE ? -1.0 : hours,
                sysLoad
        ));
    }

    @GetMapping("/results")
    @Operation(summary = "Resultados comparativos de algoritmos + KPIs simulación")
    public ResponseEntity<SimulationResultsDto> getResults() {
        SimulationConfig config = getSimConfig();
        var kpis = runtimeService.computeKpis(config);
        var collapseAt = config.getCollapseDetectedAtSim();
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST
                && collapseAt != null
                && config.getCollapseShipmentCode() != null) {
            kpis = new com.tasfb2b.dto.SimulationKpisDto(
                    kpis.deliveredOnTimePct(),
                    kpis.avgFlightOccupancyPct(),
                    kpis.avgNodeOccupancyPct(),
                    kpis.replannings(),
                    kpis.delivered(),
                    0L,
                    0L,
                    1L,
                    kpis.simulatedEvents()
            );
        }
        var startAt = config.getEffectiveScenarioStartAt();
        var endAt = resultsEndAt(config);
        Long survival = (collapseAt != null && startAt != null)
                ? Math.max(0L, java.time.Duration.between(startAt, collapseAt).getSeconds())
                : null;
        long totalShipments = startAt != null && endAt != null
                ? shipmentRepository.countByRegistrationDateBetween(startAt, endAt)
                : kpis.delivered() + kpis.delayed() + kpis.active() + kpis.critical();
        long plannedShipments = startAt != null && endAt != null
                ? travelStopRepository.countPlannedShipmentsByRegistrationBetween(startAt, endAt)
                : Math.max(0L, totalShipments - runtimeService.periodPlanningBacklog());
        return ResponseEntity.ok(new SimulationResultsDto(
                Map.of(), kpis, "INTERNAL_GA",
                config.getScenario() != null ? config.getScenario().name() : null,
                startAt != null ? startAt.toString() : null,
                endAt != null ? endAt.toString() : null,
                collapseAt != null ? collapseAt.toString() : null,
                config.getCollapseShipmentCode(),
                survival,
                totalShipments,
                plannedShipments,
                runtimeService.periodPlanningLastBatchFailed(),
                kpis.active(),
                kpis.delayed(),
                kpis.critical(),
                kpis.avgNodeOccupancyPct(),
                runtimeService.periodPlanningBacklog(),
                routePlannerService.getLastPlanningDurationMs(),
                kpis.replannings()
        ));
    }

    @GetMapping("/results/export")
    @Operation(summary = "Exportar resultados de simulación en CSV o PDF")
    public ResponseEntity<byte[]> exportResults(@RequestParam(defaultValue = "csv") String format) {
        SimulationConfig config = getSimConfig();
        var kpis = runtimeService.computeKpis(config);
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST
                && config.getCollapseDetectedAtSim() != null
                && config.getCollapseShipmentCode() != null) {
            kpis = new com.tasfb2b.dto.SimulationKpisDto(
                    kpis.deliveredOnTimePct(),
                    kpis.avgFlightOccupancyPct(),
                    kpis.avgNodeOccupancyPct(),
                    kpis.replannings(),
                    kpis.delivered(),
                    0L,
                    0L,
                    1L,
                    kpis.simulatedEvents()
            );
        }
        var endAt = resultsEndAt(config);
        SimulationResultsDto results = new SimulationResultsDto(
                Map.of(), kpis, "INTERNAL_GA",
                config.getScenario() != null ? config.getScenario().name() : null,
                config.getEffectiveScenarioStartAt() != null ? config.getEffectiveScenarioStartAt().toString() : null,
                endAt != null ? endAt.toString() : null,
                config.getCollapseDetectedAtSim() != null ? config.getCollapseDetectedAtSim().toString() : null,
                config.getCollapseShipmentCode(),
                config.getCollapseDetectedAtSim() != null && config.getEffectiveScenarioStartAt() != null
                        ? Math.max(0L, java.time.Duration.between(config.getEffectiveScenarioStartAt(), config.getCollapseDetectedAtSim()).getSeconds())
                        : null,
                kpis.delivered() + kpis.delayed() + kpis.active() + kpis.critical(),
                config.getEffectiveScenarioStartAt() != null && endAt != null
                        ? travelStopRepository.countPlannedShipmentsByRegistrationBetween(config.getEffectiveScenarioStartAt(), endAt)
                        : Math.max(0L, kpis.delivered() + kpis.delayed() + kpis.active() + kpis.critical() - runtimeService.periodPlanningBacklog()),
                runtimeService.periodPlanningLastBatchFailed(),
                kpis.active(),
                kpis.delayed(),
                kpis.critical(),
                kpis.avgNodeOccupancyPct(),
                runtimeService.periodPlanningBacklog(),
                routePlannerService.getLastPlanningDurationMs(),
                kpis.replannings()
        );

        String normalized = format == null ? "csv" : format.trim().toLowerCase();
        byte[] content;
        MediaType mediaType;
        String filename;

        if ("pdf".equals(normalized)) {
            content = simulationExportService.toPdf(results);
            mediaType = MediaType.APPLICATION_PDF;
            filename = "simulation-results.pdf";
        } else {
            content = simulationExportService.toCsv(results);
            mediaType = MediaType.parseMediaType("text/csv");
            filename = "simulation-results.csv";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(content);
    }

    /**
     * Fila de SIMULACIÓN (PERIOD/COLLAPSE), SEPARADA de la operación viva (DAY_TO_DAY = id=1, siempre on).
     * El motor procesa ambas a la vez; este controlador maneja SOLO la simulación (la operación viva no
     * se inicia/detiene desde aquí). Se crea perezosamente la primera vez que se configura/inicia un sim.
     */
    private SimulationConfig getSimConfig() {
        return configRepository.findFirstByScenarioIn(java.util.List.of(
                        com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION,
                        com.tasfb2b.model.SimulationScenario.COLLAPSE_TEST))
                .orElseGet(() -> {
                    SimulationConfig live = getConfig();
                    SimulationConfig sim = SimulationConfig.builder()
                            .scenario(com.tasfb2b.model.SimulationScenario.PERIOD_SIMULATION)
                            .isRunning(false)
                            .normalThresholdPct(live == null ? 70 : live.getNormalThresholdPct())
                            .warningThresholdPct(live == null ? 90 : live.getWarningThresholdPct())
                            .slaWarnPct(live == null ? 90 : live.getSlaWarnPct())
                            .slaCritPct(live == null ? 75 : live.getSlaCritPct())
                            .riskShipmentsWarnPct(live == null ? 10 : live.getRiskShipmentsWarnPct())
                            .riskShipmentsCritPct(live == null ? 25 : live.getRiskShipmentsCritPct())
                            .criticalNodesWarnPct(live == null ? 10 : live.getCriticalNodesWarnPct())
                            .criticalNodesCritPct(live == null ? 25 : live.getCriticalNodesCritPct())
                            .build();
                    return configRepository.save(sim);
                });
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findLiveConfigOrFirst();
        return config != null
                ? config
                : configRepository.save(SimulationConfig.builder().build());
    }

    private boolean awaitTickDrain() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while ((simulationEngineService.isTickInProgress()
                || simulationAsyncOperationsService.isPlanningInProgress()
                || simulationAsyncOperationsService.isOverdueScanInProgress())
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !simulationEngineService.isTickInProgress();
    }

    private LocalDateTime resultsEndAt(SimulationConfig config) {
        if (config == null) {
            return null;
        }
        if (config.getCollapseDetectedAtSim() != null) {
            return config.getCollapseDetectedAtSim();
        }
        LocalDateTime fixedEnd = runtimeService.resolveScenarioEnd(config);
        return runtimeService.displaySimulationTime(config,
                fixedEnd != null ? fixedEnd : config.getRuntimeSimulatedNow());
    }
}
