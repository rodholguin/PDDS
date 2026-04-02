package com.tasfb2b.controller;

import com.tasfb2b.dto.CollapseRiskDto;
import com.tasfb2b.dto.SimulationConfigUpdateDto;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.algorithm.OptimizationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Simulación", description = "Control del escenario de simulación y métricas de colapso")
public class SimulationController {

    private final SimulationConfigRepository configRepository;
    private final RoutePlannerService        routePlannerService;
    private final CollapseMonitorService     collapseMonitorService;
    private final ShipmentRepository         shipmentRepository;

    // ── Estado ────────────────────────────────────────────────────────────────

    @GetMapping("/state")
    @Operation(summary = "Obtener la configuración y estado actual de la simulación")
    public ResponseEntity<SimulationConfig> getState() {
        return ResponseEntity.ok(getConfig());
    }

    // ── Configuración ─────────────────────────────────────────────────────────

    @PostMapping("/configure")
    @Operation(summary = "Actualizar parámetros de la simulación",
               description = "No se puede configurar mientras la simulación está en curso.")
    public ResponseEntity<?> configure(@Valid @RequestBody SimulationConfigUpdateDto dto) {
        SimulationConfig config = getConfig();

        if (Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Detén la simulación antes de reconfigurarla"));
        }

        if (dto.scenario()            != null) config.setScenario(dto.scenario());
        if (dto.simulationDays()      != null) config.setSimulationDays(dto.simulationDays());
        if (dto.executionMinutes()    != null) config.setExecutionMinutes(dto.executionMinutes());
        if (dto.normalThresholdPct()  != null) config.setNormalThresholdPct(dto.normalThresholdPct());
        if (dto.warningThresholdPct() != null) config.setWarningThresholdPct(dto.warningThresholdPct());
        if (dto.primaryAlgorithm()   != null) config.setPrimaryAlgorithm(dto.primaryAlgorithm());
        if (dto.secondaryAlgorithm() != null) config.setSecondaryAlgorithm(dto.secondaryAlgorithm());

        configRepository.save(config);
        log.info("[Simulation] Configuración actualizada: escenario={}, algo={}",
                config.getScenario(), config.getPrimaryAlgorithm());
        return ResponseEntity.ok(config);
    }

    // ── Control de ejecución ──────────────────────────────────────────────────

    @PostMapping("/start")
    @Operation(summary = "Iniciar la simulación con la configuración actual")
    public ResponseEntity<?> start() {
        SimulationConfig config = getConfig();
        if (Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "La simulación ya está en curso"));
        }
        config.setIsRunning(true);
        config.setStartedAt(LocalDateTime.now());
        configRepository.save(config);
        log.info("[Simulation] Simulación INICIADA — escenario={}", config.getScenario());
        return ResponseEntity.ok(Map.of(
                "message",   "Simulación iniciada",
                "scenario",  config.getScenario(),
                "startedAt", config.getStartedAt()
        ));
    }

    @PostMapping("/stop")
    @Operation(summary = "Detener la simulación")
    public ResponseEntity<Map<String, Object>> stop() {
        SimulationConfig config = getConfig();
        config.setIsRunning(false);
        configRepository.save(config);
        log.info("[Simulation] Simulación DETENIDA");
        return ResponseEntity.ok(Map.of("message", "Simulación detenida"));
    }

    @PostMapping("/pause")
    @Operation(summary = "Pausar la simulación (equivalente a stop; sin pérdida de estado)")
    public ResponseEntity<Map<String, Object>> pause() {
        // En esta versión, pausa y stop tienen el mismo efecto sobre isRunning.
        // La diferencia semántica (reanudar vs reiniciar) se implementará
        // cuando se introduzca un campo isPaused en SimulationConfig.
        SimulationConfig config = getConfig();
        config.setIsRunning(false);
        configRepository.save(config);
        log.info("[Simulation] Simulación PAUSADA");
        return ResponseEntity.ok(Map.of("message", "Simulación pausada"));
    }

    // ── Métricas anticolapso ──────────────────────────────────────────────────

    @GetMapping("/collapse-risk")
    @Operation(summary = "Riesgo de colapso del sistema en tiempo real",
               description = "risk: 0.0 (estable) – 1.0 (colapso inminente)")
    public ResponseEntity<CollapseRiskDto> getCollapseRisk() {
        double risk    = routePlannerService.getCollapseRisk();
        double sysLoad = collapseMonitorService.computeSystemLoad();
        double hours   = collapseMonitorService.estimateTimeToCollapse();
        var bottlenecks = collapseMonitorService.getBottleneckAirports()
                .stream().map(a -> a.getIcaoCode()).toList();

        return ResponseEntity.ok(new CollapseRiskDto(
                risk,
                bottlenecks,
                hours == Double.MAX_VALUE ? -1.0 : hours,
                sysLoad
        ));
    }

    @GetMapping("/results")
    @Operation(summary = "Resultado comparativo de ambos algoritmos sobre envíos activos")
    public ResponseEntity<Map<String, OptimizationResult>> getResults() {
        var shipments = shipmentRepository.findActiveShipments();
        return ResponseEntity.ok(routePlannerService.runBothAlgorithms(shipments));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private SimulationConfig getConfig() {
        return configRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> configRepository.save(SimulationConfig.builder().build()));
    }
}
