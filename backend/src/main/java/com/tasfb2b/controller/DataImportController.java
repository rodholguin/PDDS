package com.tasfb2b.controller;

import com.tasfb2b.dto.DemandGenerationRequestDto;
import com.tasfb2b.model.DataImportLog;
import com.tasfb2b.repository.DataImportLogRepository;
import com.tasfb2b.service.BenchmarkTuningService;
import com.tasfb2b.service.BenchmarkJobService;
import com.tasfb2b.service.DataImportService;
import com.tasfb2b.service.DemandGenerationService;
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

    // ── Upload endpoints ──────────────────────────────────────────────────────

    @PostMapping(value = "/shipments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importar envíos desde CSV/Excel",
               description = "Columnas: airline_name, origin_icao, destination_icao, " +
                             "luggage_count, registration_date")
    public ResponseEntity<DataImportLog> importShipments(
            @RequestParam("file") MultipartFile file) {
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
        var result = demandGenerationService.generate(request);
        return ResponseEntity.ok(java.util.Map.of(
                "message", "Demanda generada",
                "result", result
        ));
    }
}
