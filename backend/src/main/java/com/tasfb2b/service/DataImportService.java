package com.tasfb2b.service;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReaderBuilder;
import com.tasfb2b.model.*;
import com.tasfb2b.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de importación masiva de datos desde archivos CSV o Excel (.xlsx).
 *
 * <h3>Convenciones:</h3>
 * <ul>
 *   <li>La primera fila de cualquier archivo es el encabezado y se ignora.</li>
 *   <li>Para CSV: el separador se detecta automáticamente (coma o punto y coma).</li>
 *   <li>Errores de fila individuales se registran en {@code DataImportLog.errorDetails}
 *       y el procesamiento continúa con la siguiente fila.</li>
 *   <li>Los campos opcionales no presentes usan valores por defecto documentados.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class DataImportService {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShipmentRepository    shipmentRepository;
    private final AirportRepository     airportRepository;
    private final FlightRepository      flightRepository;
    private final DataImportLogRepository importLogRepository;

    // ── Importación de Shipments ──────────────────────────────────────────────

    /**
     * Importa envíos desde un archivo CSV o Excel.
     *
     * <p>Columnas esperadas (en este orden):
     * <pre>
     *   airline_name | origin_icao | destination_icao | luggage_count | registration_date
     * </pre>
     * {@code registration_date} es opcional; si está ausente o vacío se usa {@code now()}.
     */
    public DataImportLog importShipments(MultipartFile file) {
        log.info("[Import] Importando shipments desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        int success = 0, errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2; // +1 encabezado, +1 base-1
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String airlineName  = cell(r, 0);
                String originIcao   = cell(r, 1).toUpperCase();
                String destIcao     = cell(r, 2).toUpperCase();
                int    luggageCount = Integer.parseInt(cell(r, 3).trim());
                LocalDateTime regDate = r.length > 4 && !cell(r, 4).isBlank()
                        ? LocalDateTime.parse(cell(r, 4).trim(), DT_FMT)
                        : LocalDateTime.now();

                Airport origin = airportRepository.findByIcaoCode(originIcao)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Aeropuerto origen no encontrado: " + originIcao));
                Airport dest = airportRepository.findByIcaoCode(destIcao)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Aeropuerto destino no encontrado: " + destIcao));

                Shipment shipment = Shipment.builder()
                        .shipmentCode(generateShipmentCode())
                        .airlineName(airlineName)
                        .originAirport(origin)
                        .destinationAirport(dest)
                        .luggageCount(luggageCount)
                        .registrationDate(regDate)
                        .status(ShipmentStatus.PENDING)
                        .progressPercentage(0.0)
                        .build();

                shipmentRepository.save(shipment);
                success++;

            } catch (Exception e) {
                errors++;
                errDetail.append("Fila ").append(rowNum)
                         .append(": ").append(e.getMessage()).append("\n");
                log.warn("[Import] Error en fila {}: {}", rowNum, e.getMessage());
            }
        }

        return saveLog(file.getOriginalFilename(), rows.size(), success, errors, errDetail);
    }

    // ── Importación de Airports ───────────────────────────────────────────────

    /**
     * Importa aeropuertos desde un archivo CSV o Excel.
     *
     * <p>Columnas esperadas:
     * <pre>
     *   icao_code | city | country | continent | max_storage_capacity
     * </pre>
     * {@code continent} debe ser {@code AMERICA}, {@code EUROPE} o {@code ASIA}.
     */
    public DataImportLog importAirports(MultipartFile file) {
        log.info("[Import] Importando airports desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        int success = 0, errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String  icaoCode = cell(r, 0).toUpperCase();
                String  city     = cell(r, 1);
                String  country  = cell(r, 2);
                Continent cont   = Continent.valueOf(cell(r, 3).toUpperCase().trim());
                int     maxCap   = Integer.parseInt(cell(r, 4).trim());

                // Si ya existe, actualiza; si no, crea
                Airport airport = airportRepository.findByIcaoCode(icaoCode)
                        .orElseGet(() -> Airport.builder()
                                .icaoCode(icaoCode)
                                .currentStorageLoad(0)
                                .build());
                airport.setCity(city);
                airport.setCountry(country);
                airport.setContinent(cont);
                airport.setMaxStorageCapacity(maxCap);

                airportRepository.save(airport);
                success++;

            } catch (Exception e) {
                errors++;
                errDetail.append("Fila ").append(rowNum)
                         .append(": ").append(e.getMessage()).append("\n");
            }
        }

        return saveLog(file.getOriginalFilename(), rows.size(), success, errors, errDetail);
    }

    // ── Importación de Flights ────────────────────────────────────────────────

    /**
     * Importa vuelos desde un archivo CSV o Excel.
     *
     * <p>Columnas esperadas:
     * <pre>
     *   flight_code | origin_icao | destination_icao | max_capacity |
     *   scheduled_departure | scheduled_arrival
     * </pre>
     * {@code scheduled_departure/arrival} con formato {@code yyyy-MM-dd HH:mm:ss}.
     * {@code isInterContinental} y {@code transitTimeDays} se calculan automáticamente
     * por el {@code @PrePersist} de la entidad {@link Flight}.
     */
    public DataImportLog importFlights(MultipartFile file) {
        log.info("[Import] Importando flights desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        int success = 0, errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String  flightCode  = cell(r, 0).toUpperCase();
                String  originIcao  = cell(r, 1).toUpperCase();
                String  destIcao    = cell(r, 2).toUpperCase();
                int     maxCapacity = Integer.parseInt(cell(r, 3).trim());
                LocalDateTime dep   = LocalDateTime.parse(cell(r, 4).trim(), DT_FMT);
                LocalDateTime arr   = LocalDateTime.parse(cell(r, 5).trim(), DT_FMT);

                Airport origin = airportRepository.findByIcaoCode(originIcao)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Aeropuerto origen no encontrado: " + originIcao));
                Airport dest = airportRepository.findByIcaoCode(destIcao)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Aeropuerto destino no encontrado: " + destIcao));

                // Si ya existe el código de vuelo, actualiza; si no, crea
                Flight flight = flightRepository.findByFlightCode(flightCode)
                        .orElseGet(() -> Flight.builder()
                                .flightCode(flightCode)
                                .currentLoad(0)
                                .build());
                flight.setOriginAirport(origin);
                flight.setDestinationAirport(dest);
                flight.setMaxCapacity(maxCapacity);
                flight.setScheduledDeparture(dep);
                flight.setScheduledArrival(arr);
                flight.setStatus(FlightStatus.SCHEDULED);

                flightRepository.save(flight);
                success++;

            } catch (Exception e) {
                errors++;
                errDetail.append("Fila ").append(rowNum)
                         .append(": ").append(e.getMessage()).append("\n");
            }
        }

        return saveLog(file.getOriginalFilename(), rows.size(), success, errors, errDetail);
    }

    // ── Generación de plantillas ──────────────────────────────────────────────

    /**
     * Genera y retorna un archivo Excel (.xlsx) vacío con los encabezados correctos
     * para el tipo de entidad solicitado.
     *
     * @param entityType uno de: {@code shipments}, {@code airports}, {@code flights}
     * @return bytes del archivo .xlsx listo para descargar
     * @throws IllegalArgumentException si {@code entityType} no es reconocido
     */
    public byte[] generateTemplate(String entityType) throws IOException {
        String[] headers = switch (entityType.toLowerCase()) {
            case "shipments" -> new String[]{
                "airline_name", "origin_icao", "destination_icao",
                "luggage_count", "registration_date (yyyy-MM-dd HH:mm:ss)"
            };
            case "airports" -> new String[]{
                "icao_code", "city", "country",
                "continent (AMERICA|EUROPE|ASIA)", "max_storage_capacity"
            };
            case "flights" -> new String[]{
                "flight_code", "origin_icao", "destination_icao", "max_capacity",
                "scheduled_departure (yyyy-MM-dd HH:mm:ss)",
                "scheduled_arrival (yyyy-MM-dd HH:mm:ss)"
            };
            default -> throw new IllegalArgumentException(
                "Tipo desconocido: " + entityType + ". Usa: shipments, airports o flights");
        };

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(entityType);

            // Estilo para encabezados: negrita + fondo azul claro
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000); // ~22 caracteres
            }

            // Fila de ejemplo comentada para guiar al usuario
            Row exampleRow = sheet.createRow(1);
            String[] examples = switch (entityType.toLowerCase()) {
                case "shipments" -> new String[]{"LATAM", "JFK", "BOG", "120", "2025-06-01 08:00:00"};
                case "airports"  -> new String[]{"SCL", "Santiago", "Chile", "AMERICA", "650"};
                case "flights"   -> new String[]{"FL-NEW-001", "SCL", "LIM", "180",
                                                  "2025-06-01 10:00:00", "2025-06-01 22:00:00"};
                default -> new String[]{};
            };
            for (int i = 0; i < examples.length; i++) {
                exampleRow.createCell(i).setCellValue(examples[i]);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    // ── Parsing unificado ─────────────────────────────────────────────────────

    /**
     * Detecta el formato del archivo y delega al parser correspondiente.
     * Siempre omite la primera fila (encabezado).
     */
    private List<String[]> parseFile(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".csv")) return parseCsv(file);
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return parseExcel(file);
            throw new IllegalArgumentException(
                "Formato no soportado: " + name + ". Use .csv o .xlsx");
        } catch (IOException e) {
            throw new RuntimeException("Error al leer el archivo: " + e.getMessage(), e);
        }
    }

    /**
     * Parsea un CSV detectando automáticamente el separador (coma o punto y coma).
     * Retorna todas las filas excepto la primera (encabezado).
     */
    private List<String[]> parseCsv(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");

        char separator = detectSeparator(content.lines().findFirst().orElse(""));
        log.debug("[Import] CSV separador detectado: '{}'", separator);

        try (var reader = new CSVReaderBuilder(new StringReader(content))
                .withCSVParser(new CSVParserBuilder().withSeparator(separator).build())
                .build()) {
            List<String[]> all = reader.readAll();
            return all.size() <= 1 ? new ArrayList<>() : all.subList(1, all.size());
        } catch (Exception e) {
            throw new IOException("Error parseando CSV: " + e.getMessage(), e);
        }
    }

    /**
     * Parsea un Excel (.xlsx/.xls) leyendo la primera hoja.
     * Retorna todas las filas excepto la primera (encabezado).
     */
    private List<String[]> parseExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) return new ArrayList<>();

            Row headerRow = sheet.getRow(0);
            int colCount  = headerRow == null ? 10 : headerRow.getLastCellNum();

            List<String[]> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String[] values = new String[colCount];
                for (int j = 0; j < colCount; j++) {
                    values[j] = getCellStringValue(row.getCell(j));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    /** Convierte cualquier tipo de celda Excel a String. */
    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue()
                          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf((long) cell.getNumericCellValue()); }
                catch (Exception e) { yield cell.getStringCellValue().trim(); }
            }
            default -> "";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private char detectSeparator(String firstLine) {
        long commas     = firstLine.chars().filter(c -> c == ',').count();
        long semicolons = firstLine.chars().filter(c -> c == ';').count();
        return semicolons > commas ? ';' : ',';
    }

    private String cell(String[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private boolean isBlankRow(String[] row) {
        if (row == null) return true;
        for (String s : row) if (s != null && !s.isBlank()) return false;
        return true;
    }

    private String generateShipmentCode() {
        int  year  = LocalDateTime.now().getYear();
        long count = shipmentRepository.count() + 1;
        return String.format("ENV-%d-%03d", year, count);
    }

    private DataImportLog saveLog(String fileName, int total, int success,
                                  int errors, StringBuilder errDetail) {
        ImportStatus status = errors == 0
                ? ImportStatus.SUCCESS
                : (success == 0 ? ImportStatus.FAILED : ImportStatus.PARTIAL);

        DataImportLog log = DataImportLog.builder()
                .fileName(fileName)
                .totalRows(total)
                .successRows(success)
                .errorRows(errors)
                .status(status)
                .errorDetails(errors > 0 ? errDetail.toString() : null)
                .build();

        return importLogRepository.save(log);
    }
}
