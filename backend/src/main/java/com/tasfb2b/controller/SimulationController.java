package com.tasfb2b.controller;

import com.tasfb2b.dto.CollapseRiskDto;
import com.tasfb2b.dto.AlgorithmRaceReportDto;
import com.tasfb2b.dto.SimulationConfigUpdateDto;
import com.tasfb2b.dto.SimulationEventDto;
import com.tasfb2b.dto.SimulationResultsDto;
import com.tasfb2b.dto.SimulationSpeedDto;
import com.tasfb2b.dto.SimulationStateDto;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.AlgorithmRaceService;
import com.tasfb2b.service.OperationalBootstrapService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.SimulationEngineService;
import com.tasfb2b.service.SimulationExportService;
import com.tasfb2b.service.SimulationRuntimeService;
import com.tasfb2b.service.AlgorithmProfileService;
import com.tasfb2b.service.algorithm.OptimizationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
@Tag(name = "Simulación", description = "Control del escenario de simulación y métricas de colapso")
public class SimulationController {

    private final SimulationConfigRepository configRepository;
    private final RoutePlannerService routePlannerService;
    private final CollapseMonitorService collapseMonitorService;
    private final ShipmentRepository shipmentRepository;
    private final SimulationRuntimeService runtimeService;
    private final AlgorithmRaceService algorithmRaceService;
    private final OperationalBootstrapService operationalBootstrapService;
    private final SimulationExportService simulationExportService;
    private final SimulationEngineService simulationEngineService;
    private final AlgorithmProfileService algorithmProfileService;

    @GetMapping("/state")
    @Operation(summary = "Obtener estado actual de la simulación")
    public ResponseEntity<SimulationStateDto> getState() {
        return ResponseEntity.ok(runtimeService.getState());
    }

    @PostMapping("/configure")
    @Operation(summary = "Actualizar parametros de simulación")
    public ResponseEntity<?> configure(@Valid @RequestBody SimulationConfigUpdateDto dto) {
        SimulationConfig config = getConfig();

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
        if (dto.primaryAlgorithm() != null) config.setPrimaryAlgorithm(dto.primaryAlgorithm());
        if (dto.secondaryAlgorithm() != null) config.setSecondaryAlgorithm(dto.secondaryAlgorithm());

        configRepository.save(config);
        algorithmProfileService.applyForPrimary(config.getPrimaryAlgorithm());
        return ResponseEntity.ok(runtimeService.getState());
    }

    @PostMapping("/start")
    @Operation(summary = "Iniciar simulación")
    public ResponseEntity<?> start() {
        SimulationConfig config = getConfig();
        if (Boolean.TRUE.equals(config.getIsRunning()) && !runtimeService.isPaused()) {
            return ResponseEntity.ok(Map.of(
                    "message", "La simulacion ya estaba en curso",
                    "state", runtimeService.getState()
            ));
        }

        config.setIsRunning(true);
        if (config.getStartedAt() == null) {
            config.setStartedAt(LocalDateTime.now());
        }
        configRepository.save(config);
        runtimeService.markStarted();

        return ResponseEntity.ok(Map.of(
                "message", "Simulacion iniciada",
                "state", runtimeService.getState()
        ));
    }

    @PostMapping("/stop")
    @Operation(summary = "Detener simulación y reiniciar estado operativo")
    public ResponseEntity<?> stop() {
        runtimeService.resetSimulation();

        return ResponseEntity.ok(Map.of(
                "message", "Simulacion detenida y reiniciada",
                "state", runtimeService.getState()
        ));
    }

    @PostMapping("/reset-demand")
    @Operation(summary = "Borrar todos los envíos y reiniciar operación")
    public ResponseEntity<?> resetDemand() {
        runtimeService.resetDemandKeepingNetwork();
        return ResponseEntity.ok(Map.of(
                "message", "Demanda limpiada y simulacion reiniciada",
                "state", runtimeService.getState()
        ));
    }

    @PostMapping("/pause")
    @Operation(summary = "Pausar simulación")
    public ResponseEntity<?> pause() {
        SimulationConfig config = getConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No hay simulacion en ejecucion"));
        }

        configRepository.save(config);
        runtimeService.markPaused();
        return ResponseEntity.ok(Map.of(
                "message", "Simulacion pausada",
                "state", runtimeService.getState()
        ));
    }

    @PostMapping("/speed")
    @Operation(summary = "Cambiar velocidad de simulación")
    public ResponseEntity<?> setSpeed(@Valid @RequestBody SimulationSpeedDto dto) {
        runtimeService.setSpeed(dto.speed());
        return ResponseEntity.ok(Map.of(
                "message", "Velocidad actualizada",
                "speed", runtimeService.currentSpeed(),
                "state", runtimeService.getState()
        ));
    }

    @PostMapping("/events")
    @Operation(summary = "Inyectar evento manual en simulación")
    public ResponseEntity<?> injectEvent(@Valid @RequestBody SimulationEventDto dto) {
        String result = runtimeService.injectEvent(dto);
        return ResponseEntity.ok(Map.of(
                "message", result,
                "state", runtimeService.getState()
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
        double risk = routePlannerService.getCollapseRisk();
        double sysLoad = collapseMonitorService.computeSystemLoad();
        double hours = collapseMonitorService.estimateTimeToCollapse();
        var bottlenecks = collapseMonitorService.getBottleneckAirports()
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
        var active = shipmentRepository.findActiveShipments();
        Map<String, OptimizationResult> algorithms = routePlannerService.runBothAlgorithms(active);
        String winner = routePlannerService.benchmarkWinner(active);
        return ResponseEntity.ok(new SimulationResultsDto(algorithms, runtimeService.computeKpis(), winner));
    }

    @GetMapping("/results/export")
    @Operation(summary = "Exportar resultados de simulación en CSV o PDF")
    public ResponseEntity<byte[]> exportResults(@RequestParam(defaultValue = "csv") String format) {
        var active = shipmentRepository.findActiveShipments();
        Map<String, OptimizationResult> algorithms = routePlannerService.runBothAlgorithms(active);
        String winner = routePlannerService.benchmarkWinner(active);
        SimulationResultsDto results = new SimulationResultsDto(algorithms, runtimeService.computeKpis(), winner);

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

    @GetMapping("/race-report")
    @Operation(summary = "Reporte extendido de benchmark GA vs ACO")
    public ResponseEntity<AlgorithmRaceReportDto> getRaceReport(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String scenario
    ) {
        LocalDate fromDate = (from == null || from.isBlank()) ? null : LocalDate.parse(from);
        LocalDate toDate = (to == null || to.isBlank()) ? null : LocalDate.parse(to);
        return ResponseEntity.ok(algorithmRaceService.buildRaceReport(fromDate, toDate, scenario));
    }

    @PostMapping("/seed-statistical")
    @Operation(summary = "Generar volumen inicial estadistico de envios")
    public ResponseEntity<?> seedStatistical(
            @RequestParam(required = false) Integer avg,
            @RequestParam(required = false) Integer variance
    ) {
        SimulationConfig config = getConfig();
        int effectiveAvg = avg == null ? config.getInitialVolumeAvg() : avg;
        int effectiveVariance = variance == null ? config.getInitialVolumeVariance() : variance;
        int created = operationalBootstrapService.replenishStatisticalVolume(effectiveAvg, effectiveVariance);
        return ResponseEntity.ok(Map.of(
                "message", "Volumen estadistico generado",
                "avg", effectiveAvg,
                "variance", effectiveVariance,
                "created", created,
                "state", runtimeService.getState()
        ));
    }

    @GetMapping("/initial-volume-samples")
    @Operation(summary = "Muestra N ejecuciones para validar media del generador estadistico")
    public ResponseEntity<?> initialVolumeSamples(
            @RequestParam(defaultValue = "8") Integer avg,
            @RequestParam(defaultValue = "3") Integer variance,
            @RequestParam(defaultValue = "10") Integer runs
    ) {
        int total = 0;
        var samples = new ArrayList<Integer>();
        for (int i = 0; i < Math.max(1, runs); i++) {
            int created = operationalBootstrapService.replenishStatisticalVolume(avg, variance);
            samples.add(created);
            total += created;
        }
        double mean = samples.isEmpty() ? 0.0 : (total * 1.0) / samples.size();
        return ResponseEntity.ok(Map.of(
                "avgConfigured", avg,
                "varianceConfigured", variance,
                "runs", runs,
                "samples", samples,
                "mean", mean,
                "within5pct", Math.abs(mean - avg) <= Math.max(1.0, avg * 0.05)
        ));
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : configRepository.save(SimulationConfig.builder().build());
    }
}
