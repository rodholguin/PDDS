package com.tasfb2b.controller;

import com.tasfb2b.model.DataImportLog;
import com.tasfb2b.repository.DataImportLogRepository;
import com.tasfb2b.service.DataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@Tag(name = "Importación de datos", description = "Carga masiva desde CSV o Excel")
public class DataImportController {

    private final DataImportService       importService;
    private final DataImportLogRepository importLogRepository;

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
}
