package com.tasfb2b.controller;

import com.tasfb2b.dto.DemandGenerationRequestDto;
import com.tasfb2b.dto.DatasetStatusDto;
import com.tasfb2b.dto.EnviosDatasetImportRequestDto;
import com.tasfb2b.dto.FutureDemandGenerationRequestDto;
import com.tasfb2b.dto.FutureDemandGenerationResultDto;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.DataImportLog;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.DataImportLogRepository;
import com.tasfb2b.service.BenchmarkTuningService;
import com.tasfb2b.service.BenchmarkJobService;
import com.tasfb2b.service.DataImportService;
import com.tasfb2b.service.DemandGenerationService;
import com.tasfb2b.service.EnviosImportJobService;
import com.tasfb2b.service.FutureDemandProjectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@Tag(name = "Importación de datos", description = "Carga masiva desde CSV o Excel")
public class DataImportController {

    private final DataImportService       importService;
    private final DataImportLogRepository importLogRepository;
    private final BenchmarkTuningService benchmarkTuningService;
    private final BenchmarkJobService benchmarkJobService;
    private final DemandGenerationService demandGenerationService;
    private final EnviosImportJobService enviosImportJobService;
    private final com.tasfb2b.repository.ShipmentRepository shipmentRepository;
    private final FutureDemandProjectionService futureDemandProjectionService;
    private final com.tasfb2b.repository.SimulationConfigRepository simulationConfigRepository;

    // ── Upload endpoints ──────────────────────────────────────────────────────

    @PostMapping(value = "/shipments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importar envíos desde CSV/Excel",
               description = "Columnas: airline_name, origin_icao, destination_icao, " +
                             "luggage_count, registration_date")
    public ResponseEntity<DataImportLog> importShipments(
            @RequestParam("file") MultipartFile file) {
        markProjectedDemandAsStale();
        DataImportLog result = importService.importShipments(file);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/airports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importar aeropuertos desde CSV/Excel",
               description = "Columnas: icao_code, city, country, continent, max_storage_capacity")
    public ResponseEntity<DataImportLog> importAirports(
            @RequestParam("file") MultipartFile file) {
        DataImportLog result = importService.importAirports(file);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/flights", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importar vuelos desde CSV/Excel",
               description = "Columnas: flight_code, origin_icao, destination_icao, " +
                             "max_capacity, scheduled_departure, scheduled_arrival")
    public ResponseEntity<DataImportLog> importFlights(
            @RequestParam("file") MultipartFile file) {
        DataImportLog result = importService.importFlights(file);
        return ResponseEntity.ok(result);
    }

    // ── Template download ─────────────────────────────────────────────────────

    @GetMapping("/template/{type}")
    @Operation(summary = "Descargar plantilla Excel vacía",
               description = "type: shipments | airports | flights")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String type) throws IOException {
        byte[] bytes = importService.generateTemplate(type);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"template-" + type + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @GetMapping("/logs")
    @Operation(summary = "Historial de importaciones")
    public ResponseEntity<List<DataImportLog>> getLogs() {
        return ResponseEntity.ok(importLogRepository.findTop10ByOrderByImportedAtDesc());
    }

    @GetMapping("/dataset-status")
    @Operation(summary = "Estado agregado del dataset de envios")
    public ResponseEntity<DatasetStatusDto> datasetStatus() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        return ResponseEntity.ok(new DatasetStatusDto(
                shipmentRepository.count(),
                shipmentRepository.countByStatus(ShipmentStatus.PENDING),
                shipmentRepository.countByStatus(ShipmentStatus.IN_ROUTE),
                shipmentRepository.countByStatus(ShipmentStatus.DELIVERED),
                shipmentRepository.countByStatus(ShipmentStatus.DELAYED),
                shipmentRepository.countByStatus(ShipmentStatus.CRITICAL),
                config != null && Boolean.TRUE.equals(config.getProjectedDemandReady()),
                config == null ? null : config.getProjectedHistoricalFrom(),
                config == null ? null : config.getProjectedHistoricalTo(),
                config == null ? null : config.getProjectedFrom(),
                config == null ? null : config.getProjectedTo(),
                config == null ? null : config.getProjectedGeneratedAt()
        ));
    }

    @PostMapping("/dataset/default")
    @Operation(summary = "Importar dataset real desde /datos")
    public ResponseEntity<?> importDefaultDataset() {
        DataImportService.DatasetImportSummary summary = importService.importDefaultDataset();
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Dataset real importado",
                "airports", summary.airports(),
                "flights", summary.flights()
        ));
    }

    @GetMapping("/template/shipments-scenarios")
    @Operation(summary = "Descargar archivo de demanda de escenarios para benchmark")
    public ResponseEntity<byte[]> downloadScenarioDemandTemplate() {
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder();
        csv.append("airline_name,origin_icao,destination_icao,luggage_count,registration_date\n");

        benchmarkTuningService.buildDefaultScenarioRows().forEach(row -> csv
                .append(row.airlineName()).append(',')
                .append(row.originIcao()).append(',')
                .append(row.destinationIcao()).append(',')
                .append(row.luggageCount()).append(',')
                .append(row.registrationDate().format(dt))
                .append('\n')
        );

        byte[] payload = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"demand-scenarios.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(payload);
    }

    @PostMapping("/benchmark/run")
    @Operation(summary = "Ejecutar benchmark y tuning con escenarios de demanda")
    public ResponseEntity<?> runBenchmarkAndTune() {
        markProjectedDemandAsStale();
        var rows = benchmarkTuningService.buildDefaultScenarioRows();
        int created = benchmarkTuningService.generateDemandFromScenarioRows(rows);
        var summary = benchmarkTuningService.runBenchmarkAndTune();
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Benchmark ejecutado",
                "generatedRows", rows.size(),
                "createdShipments", created,
                "winner", summary.winner(),
                "sampleSize", summary.sampleSize(),
                "results", summary.results(),
                "scenarios", summary.scenarios(),
                "rows", summary.rows(),
                "bestProfile", summary.bestProfile()
        ));
    }

    @PostMapping("/benchmark/start")
    @Operation(summary = "Iniciar benchmark asíncrono")
    public ResponseEntity<?> startBenchmarkJob() {
        importService.importDefaultDataset();
        String jobId = benchmarkJobService.start();
        return ResponseEntity.accepted().body(java.util.Map.of(
                "message", "Benchmark iniciado",
                "jobId", jobId
        ));
    }

    @GetMapping("/benchmark/status/{jobId}")
    @Operation(summary = "Consultar estado de benchmark asíncrono")
    public ResponseEntity<?> benchmarkStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(benchmarkJobService.get(jobId));
    }

    @GetMapping("/benchmark/status")
    @Operation(summary = "Consultar último benchmark asíncrono")
    public ResponseEntity<?> latestBenchmarkStatus() {
        var latest = benchmarkJobService.latest();
        if (latest == null) {
            return ResponseEntity.ok(java.util.Map.of(
                    "status", "IDLE",
                    "message", "No hay benchmark ejecutado"
            ));
        }
        return ResponseEntity.ok(latest);
    }

    @PostMapping("/demand/generate")
    @Operation(summary = "Generar demanda masiva por escenario")
    public ResponseEntity<?> generateDemand(@Valid @RequestBody DemandGenerationRequestDto request) {
        markProjectedDemandAsStale();
        var result = demandGenerationService.generate(request);
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Demanda generada",
                "result", result
        ));
    }

    @PostMapping("/demand/project-future")
    @Operation(summary = "Generar demanda futura (24 meses) basada en historica")
    public ResponseEntity<FutureDemandGenerationResultDto> projectFutureDemand(
            @Valid @RequestBody FutureDemandGenerationRequestDto request
    ) {
        return ResponseEntity.ok(futureDemandProjectionService.generate(request));
    }

    @PostMapping("/demand/repair-coverage")
    @Operation(summary = "Reparar huecos diarios de demanda proyectada")
    public ResponseEntity<?> repairProjectedCoverage(@Valid @RequestBody FutureDemandGenerationRequestDto request) {
        int inserted = futureDemandProjectionService.repairProjectedDailyCoverage(request.projectionStart(), request.projectionEnd());
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Cobertura diaria reparada",
                "insertedDays", inserted,
                "from", request.projectionStart(),
                "to", request.projectionEnd()
        ));
    }

    @PostMapping("/shipments/dataset")
    @Operation(summary = "Importar envios desde carpeta /datos/envios con muestreo reproducible")
    public ResponseEntity<?> importShipmentsDataset(@RequestBody(required = false) EnviosDatasetImportRequestDto request) {
        markProjectedDemandAsStale();
        var result = importService.importShipmentsFromEnviosDataset(request);
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Dataset de envios importado",
                "result", result
        ));
    }

    @PostMapping("/shipments/dataset/full")
    @Operation(summary = "Importar todos los envios oficiales desde /datos/envios")
    public ResponseEntity<?> importFullShipmentsDataset() {
        markProjectedDemandAsStale();
        var result = importService.importShipmentsFromEnviosDataset(
                new EnviosDatasetImportRequestDto(7, 60, 50000, null, true, null)
        );
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Dataset completo de envios importado",
                "result", result
        ));
    }

    @PostMapping("/shipments/dataset/full/start")
    @Operation(summary = "Iniciar importacion completa de envios en modo asíncrono")
    public ResponseEntity<?> startFullShipmentsDatasetImport() {
        markProjectedDemandAsStale();
        String jobId = enviosImportJobService.startFullImport();
        return ResponseEntity.accepted().body(java.util.Map.of(
                "message", "Importacion full de envios iniciada",
                "jobId", jobId
        ));
    }

    @GetMapping("/shipments/dataset/full/status/{jobId}")
    @Operation(summary = "Consultar estado de importacion completa de envios")
    public ResponseEntity<?> fullShipmentsDatasetImportStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(enviosImportJobService.get(jobId));
    }

    @GetMapping("/shipments/dataset/full/status")
    @Operation(summary = "Consultar ultimo estado de importacion completa de envios")
    public ResponseEntity<?> latestFullShipmentsDatasetImportStatus() {
        var latest = enviosImportJobService.latest();
        if (latest == null) {
            return ResponseEntity.ok(java.util.Map.of(
                    "status", "IDLE",
                    "message", "No hay importacion full ejecutada"
            ));
        }
        return ResponseEntity.ok(latest);
    }

    private void markProjectedDemandAsStale() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        if (config == null) {
            config = SimulationConfig.builder().build();
        }
        config.setProjectedDemandReady(false);
        config.setProjectedHistoricalFrom(null);
        config.setProjectedHistoricalTo(null);
        config.setProjectedFrom(null);
        config.setProjectedTo(null);
        config.setProjectedGeneratedAt(null);
        simulationConfigRepository.save(config);
    }
}
