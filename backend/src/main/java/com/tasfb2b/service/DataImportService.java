package com.tasfb2b.service;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReaderBuilder;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import com.tasfb2b.model.DataImportLog;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.ImportStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.dto.EnviosDatasetImportRequestDto;
import com.tasfb2b.dto.EnviosDatasetImportResultDto;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.DataImportLogRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.GlobalSequenceRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeMap;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class DataImportService {

    private static final int FULL_DATASET_ROUTE_PREPLAN_LIMIT = 0;
    private static final int FULL_DATASET_BATCH_SIZE = 1000;
    private static final long FULL_DATASET_SEQUENCE_BLOCK_SIZE = 5000L;
    private static final DateTimeFormatter CODE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern AIRPORT_LINE_PATTERN = Pattern.compile(
            "^\\s*(\\d{2})\\s+([A-Z]{4})\\s+(.+?)\\s{2,}(.+?)\\s{2,}\\S+\\s+([+-]\\d+)\\s+(\\d+)\\s+Latitude:.*$"
    );
    private static final Pattern LAT_PATTERN = Pattern.compile(
            "Latitude:\\s*(\\d{1,2})°\\s*(\\d{1,2})'\\s*(\\d{1,2})[\\\"']\\s*([NS])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LON_PATTERN = Pattern.compile(
            "Longitude:\\s*(\\d{1,3})°\\s*(\\d{1,2})'\\s*(\\d{1,2})[\\\"']\\s*([EW])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FLIGHT_LINE_PATTERN = Pattern.compile(
            "^\\s*([A-Z]{4})-([A-Z]{4})-([0-9]{1,2}:[0-9]{2})-([0-9]{1,2}:[0-9]{2})-(\\d{4})\\s*$"
    );
    private static final Pattern ENVIOS_FILE_PATTERN = Pattern.compile("^_envios_([A-Z]{4})_\\.txt$");
    private static final Pattern ENVIOS_LINE_PATTERN = Pattern.compile(
            "^(\\d+)-(\\d{8})-(\\d{2})-(\\d{2})-([A-Z]{4})-(\\d{3})-(\\d{7})$"
    );

    private final ShipmentRepository shipmentRepository;
    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final TravelStopRepository travelStopRepository;
    private final DataImportLogRepository importLogRepository;
    private final RoutePlannerService routePlannerService;
    private final SimulationConfigRepository simulationConfigRepository;
    private final ShipmentCodeService shipmentCodeService;
    private final ShipmentCodeRangeService shipmentCodeRangeService;

    public record DatasetImportSummary(DataImportLog airports, DataImportLog flights) {}

    public DatasetImportSummary importDefaultDataset() {
        Path airportsPath = resolveDatasetFile(
                List.of(
                        "datos/c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt",
                        "../datos/c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt",
                        "/app/datos/c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt",
                        "datos/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt",
                        "../datos/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt",
                        "/app/datos/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt"
                ),
                "aeropuerto"
        );
        Path flightsPath = resolveDatasetFile(
                List.of(
                        "datos/c.1inf54.25.2.planes_vuelo.v4.20250818.txt",
                        "../datos/c.1inf54.25.2.planes_vuelo.v4.20250818.txt",
                        "/app/datos/c.1inf54.25.2.planes_vuelo.v4.20250818.txt",
                        "datos/planes_vuelo.txt",
                        "../datos/planes_vuelo.txt",
                        "/app/datos/planes_vuelo.txt"
                ),
                "planes_vuelo"
        );
        return importRealDataset(airportsPath, flightsPath);
    }

    public DatasetImportSummary importRealDataset(Path airportsPath, Path flightsPath) {
        DataImportLog airports = importAirportsFromDataset(airportsPath);
        DataImportLog flights = importFlightsFromDataset(flightsPath);
        return new DatasetImportSummary(airports, flights);
    }

    public DataImportLog importShipments(MultipartFile file) {
        log.info("[Import] Importando shipments desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        // Snapshot reusable network for this batch to avoid repeated heavy queries per row.
        List<Airport> airportsSnapshot = airportRepository.findAll();
        List<Flight> flightsSnapshot = flightRepository.findFlightsWithAvailableCapacity();
        LocalDateTime flightWindowBase = flightsSnapshot.stream()
                .map(flight -> flight.getScheduledDeparture())
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        HashMap<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airportsSnapshot) {
            airportByIcao.put(airport.getIcaoCode().toUpperCase(Locale.ROOT), airport);
        }

        Set<Flight> touchedFlights = new HashSet<>();

        int success = 0;
        int errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String airlineName = cell(r, 0);
                String originIcao = cell(r, 1).toUpperCase(Locale.ROOT);
                String destIcao = cell(r, 2).toUpperCase(Locale.ROOT);
                int luggageCount = Integer.parseInt(cell(r, 3).trim());
                LocalDateTime regDate = r.length > 4 && !cell(r, 4).isBlank()
                        ? LocalDateTime.parse(cell(r, 4).trim(), DT_FMT)
                        : LocalDateTime.now();

                Airport origin = Optional.ofNullable(airportByIcao.get(originIcao))
                        .orElseThrow(() -> new IllegalArgumentException("Aeropuerto origen no encontrado: " + originIcao));
                Airport dest = Optional.ofNullable(airportByIcao.get(destIcao))
                        .orElseThrow(() -> new IllegalArgumentException("Aeropuerto destino no encontrado: " + destIcao));

                Shipment shipment = Shipment.builder()
                        .shipmentCode(shipmentCodeService.nextCode(regDate))
                        .airlineName(airlineName)
                        .originAirport(origin)
                        .destinationAirport(dest)
                        .luggageCount(luggageCount)
                        .registrationDate(regDate)
                        .status(ShipmentStatus.PENDING)
                        .progressPercentage(0.0)
                        .build();
                shipmentRepository.save(shipment);

                List<com.tasfb2b.model.TravelStop> plannedStops = routePlannerService.planShipment(
                        shipment,
                        activeAlgorithmName(),
                        flightsSnapshot,
                        airportsSnapshot,
                        false
                );

                if (plannedStops.isEmpty() || shipment.getStatus() == ShipmentStatus.CRITICAL) {
                    throw new IllegalArgumentException("No se encontro ruta factible para el envio");
                }

                for (com.tasfb2b.model.TravelStop stop : plannedStops) {
                    if (stop.getFlight() != null) touchedFlights.add(stop.getFlight());
                }

                if (shipment.getStatus() == ShipmentStatus.CRITICAL) {
                    throw new IllegalArgumentException("No se encontro ruta factible para el envio");
                }

                success++;

            } catch (Exception e) {
                errors++;
                errDetail.append("Fila ").append(rowNum)
                        .append(": ").append(e.getMessage()).append("\n");
            }
        }

        if (!touchedFlights.isEmpty()) {
            flightRepository.saveAll(touchedFlights);
        }

        return saveLog(file.getOriginalFilename(), rows.size(), success, errors, errDetail);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EnviosDatasetImportResultDto importShipmentsFromEnviosDataset(EnviosDatasetImportRequestDto request) {
        int seed = request == null || request.seed() == null ? 7 : request.seed();
        boolean fullDataset = request != null && Boolean.TRUE.equals(request.fullDataset());
        int maxAirports = request == null || request.maxAirports() == null ? (fullDataset ? 60 : 12) : request.maxAirports();
        int maxPerAirport = request == null || request.maxPerAirport() == null ? (fullDataset ? 50000 : 400) : request.maxPerAirport();
        String algorithm = (request == null || request.algorithmName() == null || request.algorithmName().isBlank())
                ? activeAlgorithmName()
                : request.algorithmName().trim();

        Path enviosDir = resolveDefaultPath("datos/envios", "../datos/envios", "/app/datos/envios");
        if (!Files.isDirectory(enviosDir)) {
            throw new IllegalStateException("No se encontro directorio de envios: " + enviosDir);
        }

        List<Path> files;
        try {
            files = Files.list(enviosDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> ENVIOS_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron listar archivos de envios", e);
        }

        List<Path> selectedFiles = fullDataset
                ? files
                : selectEnviosFiles(files, request == null ? null : request.includeOrigins(), seed, maxAirports);

        List<Airport> airportsSnapshot = airportRepository.findAll();
        List<Flight> flightsSnapshot = flightRepository.findFlightsWithAvailableCapacity();
        LocalDateTime flightWindowBase = flightsSnapshot.stream()
                .map(flight -> flight.getScheduledDeparture())
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        HashMap<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airportsSnapshot) {
            airportByIcao.put(airport.getIcaoCode().toUpperCase(Locale.ROOT), airport);
        }

        Set<Flight> touchedFlights = new HashSet<>();
        List<Shipment> fullDatasetBuffer = new ArrayList<>();
        long fullDatasetNextSequence = 0L;
        long fullDatasetSequenceEnd = -1L;
        int requestedRows = 0;
        int importedRows = 0;
        int failedRows = 0;
        int processedFiles = 0;
        Map<String, Integer> failureByCause = new TreeMap<>();

        java.util.function.Consumer<String> failureCounter = cause ->
                failureByCause.merge(cause, 1, Integer::sum);

        for (Path file : selectedFiles) {
            Matcher nameMatcher = ENVIOS_FILE_PATTERN.matcher(file.getFileName().toString());
            if (!nameMatcher.matches()) continue;
            processedFiles++;
            String originIcao = nameMatcher.group(1).toUpperCase(Locale.ROOT);
            Airport origin = airportByIcao.get(originIcao);
            if (origin == null) {
                failureCounter.accept("ORIGIN_NOT_FOUND");
                continue;
            }

            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                failedRows += maxPerAirport;
                failureCounter.accept("FILE_READ_ERROR");
                continue;
            }

            int processedInFile = 0;
            for (String raw : lines) {
                if (processedInFile >= maxPerAirport) break;
                String line = sanitizeLine(raw);
                if (line.isBlank()) continue;

                requestedRows++;
                Matcher lineMatcher = ENVIOS_LINE_PATTERN.matcher(line);
                if (!lineMatcher.matches()) {
                    failedRows++;
                    failureCounter.accept("PARSE_INVALID_FORMAT");
                    processedInFile++;
                    continue;
                }

                try {
                    String yyyymmdd = lineMatcher.group(2);
                    String hh = lineMatcher.group(3);
                    String mm = lineMatcher.group(4);
                    String destIcao = lineMatcher.group(5).toUpperCase(Locale.ROOT);
                    int luggageCount = Integer.parseInt(lineMatcher.group(6));
                    String clientId = lineMatcher.group(7);

                    Airport destination = airportByIcao.get(destIcao);
                    if (destination == null || destination.getId().equals(origin.getId())) {
                        failedRows++;
                        failureCounter.accept("DESTINATION_INVALID");
                        processedInFile++;
                        continue;
                    }

                    LocalDateTime registrationDate = LocalDateTime.parse(
                            yyyymmdd + hh + mm,
                            DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                    );

                    if (flightWindowBase != null) {
                        registrationDate = registrationDate
                                .withYear(flightWindowBase.getYear())
                                .withMonth(flightWindowBase.getMonthValue())
                                .withDayOfMonth(flightWindowBase.getDayOfMonth());
                    }

                    Shipment shipment = Shipment.builder()
                            .shipmentCode(fullDataset
                                    ? null
                                    : shipmentCodeService.nextCode(registrationDate))
                            .airlineName("DATASET-" + clientId)
                            .originAirport(origin)
                            .destinationAirport(destination)
                            .luggageCount(luggageCount)
                            .registrationDate(registrationDate)
                            .status(ShipmentStatus.PENDING)
                            .progressPercentage(0.0)
                            .build();

                    if (!fullDataset || importedRows < FULL_DATASET_ROUTE_PREPLAN_LIMIT) {
                        shipmentRepository.save(shipment);
                        List<TravelStop> plannedStops = routePlannerService.planShipment(
                                shipment,
                                algorithm,
                                flightsSnapshot,
                                airportsSnapshot,
                                false
                        );

                        if (plannedStops.isEmpty() || shipment.getStatus() == ShipmentStatus.CRITICAL) {
                            failedRows++;
                            failureCounter.accept("NO_FEASIBLE_ROUTE");
                            processedInFile++;
                            continue;
                        }

                        for (TravelStop stop : plannedStops) {
                            if (stop.getFlight() != null) {
                                touchedFlights.add(stop.getFlight());
                            }
                        }
                    } else {
                        if (fullDatasetNextSequence > fullDatasetSequenceEnd) {
                            ShipmentCodeRangeService.SequenceRange range = shipmentCodeRangeService.allocateRange(FULL_DATASET_SEQUENCE_BLOCK_SIZE);
                            fullDatasetNextSequence = range.startInclusive();
                            fullDatasetSequenceEnd = range.endInclusive();
                        }
                        shipment.setShipmentCode(generateBufferedCode(fullDatasetNextSequence, registrationDate));
                        fullDatasetNextSequence++;
                        fullDatasetBuffer.add(shipment);
                        if (fullDatasetBuffer.size() >= FULL_DATASET_BATCH_SIZE) {
                            shipmentRepository.saveAll(fullDatasetBuffer);
                            shipmentRepository.flush();
                            fullDatasetBuffer.clear();
                        }
                    }

                    importedRows++;
                    processedInFile++;
                } catch (Exception ex) {
                    failedRows++;
                    failureCounter.accept("PROCESSING_EXCEPTION");
                    processedInFile++;
                }
            }
        }

        if (!fullDatasetBuffer.isEmpty()) {
            shipmentRepository.saveAll(fullDatasetBuffer);
            shipmentRepository.flush();
            fullDatasetBuffer.clear();
        }

        if (requestedRows != importedRows + failedRows) {
            failureByCause.merge("ACCOUNTING_MISMATCH", Math.max(0, requestedRows - (importedRows + failedRows)), Integer::sum);
        }

        if (!touchedFlights.isEmpty()) {
            flightRepository.saveAll(touchedFlights);
        }
        travelStopRepository.flush();

        StringBuilder details = new StringBuilder();
        details.append("processedFiles=").append(processedFiles)
                .append("/").append(selectedFiles.size())
                .append(", requestedRows=").append(requestedRows)
                .append(", importedRows=").append(importedRows)
                .append(", failedRows=").append(failedRows)
                .append("\n");
        failureByCause.forEach((key, value) -> details.append(key).append('=').append(value).append('\n'));
        saveLog("envios-dataset" + (fullDataset ? "-full" : "-sample"), requestedRows, importedRows, failedRows, details);

        return new EnviosDatasetImportResultDto(
                files.size(),
                selectedFiles.size(),
                processedFiles,
                files.size(),
                requestedRows,
                importedRows,
                failedRows,
                failureByCause,
                selectedFiles.stream()
                        .map(path -> {
                            Matcher matcher = ENVIOS_FILE_PATTERN.matcher(path.getFileName().toString());
                            return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : path.getFileName().toString();
                        })
                        .toList(),
                algorithm
        );
    }

    public DataImportLog importAirports(MultipartFile file) {
        log.info("[Import] Importando airports desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        int success = 0;
        int errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String icaoCode = cell(r, 0).toUpperCase(Locale.ROOT);
                String city = cell(r, 1);
                String country = cell(r, 2);
                Continent cont = Continent.valueOf(cell(r, 3).toUpperCase(Locale.ROOT).trim());
                int maxCap = Integer.parseInt(cell(r, 4).trim());
                double latitude = optionalCoordinate(r, 5);
                double longitude = optionalCoordinate(r, 6);

                Airport airport = airportRepository.findByIcaoCode(icaoCode)
                        .orElseGet(() -> Airport.builder()
                                .icaoCode(icaoCode)
                                .currentStorageLoad(0)
                                .latitude(0.0)
                                .longitude(0.0)
                                .build());
                airport.setCity(city);
                airport.setCountry(country);
                airport.setContinent(cont);
                airport.setMaxStorageCapacity(maxCap);
                airport.setLatitude(latitude);
                airport.setLongitude(longitude);

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

    public DataImportLog importFlights(MultipartFile file) {
        log.info("[Import] Importando flights desde '{}'", file.getOriginalFilename());
        List<String[]> rows = parseFile(file);

        int success = 0;
        int errors = 0;
        StringBuilder errDetail = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            try {
                String[] r = rows.get(i);
                if (isBlankRow(r)) continue;

                String flightCode = cell(r, 0).toUpperCase(Locale.ROOT);
                String originIcao = cell(r, 1).toUpperCase(Locale.ROOT);
                String destIcao = cell(r, 2).toUpperCase(Locale.ROOT);
                int maxCapacity = Integer.parseInt(cell(r, 3).trim());
                LocalDateTime dep = LocalDateTime.parse(cell(r, 4).trim(), DT_FMT);
                LocalDateTime arr = LocalDateTime.parse(cell(r, 5).trim(), DT_FMT);

                Airport origin = airportRepository.findByIcaoCode(originIcao)
                        .orElseThrow(() -> new IllegalArgumentException("Aeropuerto origen no encontrado: " + originIcao));
                Airport dest = airportRepository.findByIcaoCode(destIcao)
                        .orElseThrow(() -> new IllegalArgumentException("Aeropuerto destino no encontrado: " + destIcao));

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

    public byte[] generateTemplate(String entityType) throws IOException {
        String[] headers = switch (entityType.toLowerCase(Locale.ROOT)) {
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
                sheet.setColumnWidth(i, 6000);
            }

            Row exampleRow = sheet.createRow(1);
            String[] examples = switch (entityType.toLowerCase(Locale.ROOT)) {
                case "shipments" -> new String[]{"LATAM", "SKBO", "SEQM", "120", "2025-06-01 08:00:00"};
                case "airports" -> new String[]{"SKBO", "Bogota", "Colombia", "AMERICA", "430"};
                case "flights" -> new String[]{"FP0001", "SKBO", "SEQM", "300", "2025-06-01 03:34:00", "2025-06-01 05:21:00"};
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

    private DataImportLog importAirportsFromDataset(Path filePath) {
        int success = 0;
        int errors = 0;
        StringBuilder details = new StringBuilder();
        int total = 0;
        Continent currentContinent = null;

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_16BE);
            List<String> lines = content.lines().toList();

            for (int i = 0; i < lines.size(); i++) {
                String raw = sanitizeLine(lines.get(i));
                if (raw.isBlank()) continue;

                String lower = raw.toLowerCase(Locale.ROOT);
                if (lower.contains("america del sur")) {
                    currentContinent = Continent.AMERICA;
                    continue;
                }
                if (lower.startsWith("europa")) {
                    currentContinent = Continent.EUROPE;
                    continue;
                }
                if (lower.startsWith("asia")) {
                    currentContinent = Continent.ASIA;
                    continue;
                }

                if (!raw.matches("^\\d{2}\\s+.*")) {
                    continue;
                }

                total++;
                try {
                    ParsedAirportRow row = parseAirportDatasetLine(raw, currentContinent);
                    Airport airport = airportRepository.findByIcaoCode(row.icao())
                            .orElseGet(() -> Airport.builder()
                                    .icaoCode(row.icao())
                                    .currentStorageLoad(0)
                                    .build());
                    airport.setCity(row.city());
                    airport.setCountry(row.country());
                    airport.setContinent(row.continent());
                    airport.setMaxStorageCapacity(row.capacity());
                    airport.setLatitude(row.latitude());
                    airport.setLongitude(row.longitude());
                    if (airport.getCurrentStorageLoad() == null) {
                        airport.setCurrentStorageLoad(0);
                    }
                    airportRepository.save(airport);
                    success++;
                } catch (Exception ex) {
                    errors++;
                    details.append("Linea ").append(i + 1).append(": ").append(ex.getMessage()).append("\n");
                }
            }

        } catch (IOException ex) {
            errors++;
            details.append("No se pudo leer archivo: ").append(ex.getMessage()).append("\n");
        }

        return saveLog(filePath.getFileName().toString(), total, success, errors, details);
    }

    private DataImportLog importFlightsFromDataset(Path filePath) {
        int success = 0;
        int errors = 0;
        StringBuilder details = new StringBuilder();
        int total = 0;
        LocalDate baseDate = LocalDate.now();

        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String raw = sanitizeLine(lines.get(i));
                if (raw.isBlank()) continue;
                total++;

                try {
                    Matcher matcher = FLIGHT_LINE_PATTERN.matcher(raw);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("Formato no valido");
                    }

                    String originIcao = matcher.group(1);
                    String destinationIcao = matcher.group(2);
                    int departureMinutes = parseExtendedHourMinutes(matcher.group(3));
                    int arrivalMinutes = parseExtendedHourMinutes(matcher.group(4));
                    int maxCapacity = Integer.parseInt(matcher.group(5));

                    if (maxCapacity <= 0) {
                        throw new IllegalArgumentException("Capacidad invalida: " + maxCapacity);
                    }

                    Airport origin = airportRepository.findByIcaoCode(originIcao)
                            .orElseThrow(() -> new IllegalArgumentException("Origen no encontrado: " + originIcao));
                    Airport destination = airportRepository.findByIcaoCode(destinationIcao)
                            .orElseThrow(() -> new IllegalArgumentException("Destino no encontrado: " + destinationIcao));

                    LocalDateTime departure = baseDate.atStartOfDay().plusMinutes(departureMinutes);
                    LocalDateTime arrival = baseDate.atStartOfDay().plusMinutes(arrivalMinutes);
                    if (!arrival.isAfter(departure)) {
                        arrival = arrival.plusDays(1);
                    }

                    String flightCode = String.format("FP%04d", i + 1);
                    Flight flight = flightRepository.findByFlightCode(flightCode)
                            .orElseGet(() -> Flight.builder()
                                    .flightCode(flightCode)
                                    .currentLoad(0)
                                    .build());

                    flight.setOriginAirport(origin);
                    flight.setDestinationAirport(destination);
                    flight.setScheduledDeparture(departure);
                    flight.setScheduledArrival(arrival);
                    flight.setMaxCapacity(maxCapacity);
                    flight.setStatus(FlightStatus.SCHEDULED);
                    if (flight.getCurrentLoad() == null) {
                        flight.setCurrentLoad(0);
                    }

                    flightRepository.save(flight);
                    success++;
                } catch (Exception ex) {
                    errors++;
                    details.append("Linea ").append(i + 1).append(": ").append(ex.getMessage()).append("\n");
                }
            }
        } catch (IOException ex) {
            errors++;
            details.append("No se pudo leer archivo: ").append(ex.getMessage()).append("\n");
        }

        return saveLog(filePath.getFileName().toString(), total, success, errors, details);
    }

    private ParsedAirportRow parseAirportDatasetLine(String rawLine, Continent fallbackContinent) {
        Matcher prefixMatcher = AIRPORT_LINE_PATTERN.matcher(rawLine);
        if (!prefixMatcher.matches()) {
            throw new IllegalArgumentException("No se pudo extraer cabecera de aeropuerto");
        }

        String icao = prefixMatcher.group(2);
        String city = normalizeText(prefixMatcher.group(3));
        String country = normalizeText(prefixMatcher.group(4));
        int capacity = Integer.parseInt(prefixMatcher.group(6));

        Matcher latMatcher = LAT_PATTERN.matcher(rawLine);
        Matcher lonMatcher = LON_PATTERN.matcher(rawLine);
        if (!latMatcher.find() || !lonMatcher.find()) {
            throw new IllegalArgumentException("No se pudo extraer coordenadas");
        }

        double latitude = dmsToDecimal(
                Integer.parseInt(latMatcher.group(1)),
                Integer.parseInt(latMatcher.group(2)),
                Integer.parseInt(latMatcher.group(3)),
                latMatcher.group(4)
        );
        double longitude = dmsToDecimal(
                Integer.parseInt(lonMatcher.group(1)),
                Integer.parseInt(lonMatcher.group(2)),
                Integer.parseInt(lonMatcher.group(3)),
                lonMatcher.group(4)
        );

        Continent continent = Optional.ofNullable(fallbackContinent)
                .orElseThrow(() -> new IllegalArgumentException("Continente no detectado para fila"));

        return new ParsedAirportRow(icao, city, country, continent, capacity, latitude, longitude);
    }

    private Path resolveDefaultPath(String... candidates) {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }

        for (String candidate : candidates) {
            try {
                ClassPathResource resource = new ClassPathResource(candidate);
                if (resource.exists()) {
                    return resource.getFile().toPath();
                }
            } catch (IOException ignored) {
                // Try next candidate.
            }
        }

        throw new IllegalStateException("No se encontro dataset en rutas esperadas");
    }

    private Path resolveDatasetFile(List<String> explicitCandidates, String containsToken) {
        for (String candidate : explicitCandidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
            try {
                ClassPathResource resource = new ClassPathResource(candidate);
                if (resource.exists()) {
                    return resource.getFile().toPath();
                }
            } catch (IOException ignored) {
                // continue
            }
        }

        List<Path> roots = List.of(Path.of("datos"), Path.of("../datos"), Path.of("/app/datos"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.list(root)) {
                Optional<Path> candidate = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(containsToken.toLowerCase(Locale.ROOT)))
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get();
                }
            } catch (IOException ignored) {
                // try next root
            }
        }

        throw new IllegalStateException("No se encontro dataset en rutas esperadas");
    }

    private List<String[]> parseFile(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".csv")) return parseCsv(file);
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return parseExcel(file);
            throw new IllegalArgumentException("Formato no soportado: " + name + ". Use .csv o .xlsx");
        } catch (IOException e) {
            throw new RuntimeException("Error al leer el archivo: " + e.getMessage(), e);
        }
    }

    private List<String[]> parseCsv(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");

        char separator = detectSeparator(content.lines().findFirst().orElse(""));

        try (var reader = new CSVReaderBuilder(new StringReader(content))
                .withCSVParser(new CSVParserBuilder().withSeparator(separator).build())
                .build()) {
            List<String[]> all = reader.readAll();
            return all.size() <= 1 ? new ArrayList<>() : all.subList(1, all.size());
        } catch (Exception e) {
            throw new IOException("Error parseando CSV: " + e.getMessage(), e);
        }
    }

    private List<String[]> parseExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) return new ArrayList<>();

            Row headerRow = sheet.getRow(0);
            int colCount = headerRow == null ? 10 : headerRow.getLastCellNum();

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

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().format(DT_FMT)
                    : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf((long) cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue().trim();
                }
            }
            default -> "";
        };
    }

    private char detectSeparator(String firstLine) {
        long commas = firstLine.chars().filter(c -> c == ',').count();
        long semicolons = firstLine.chars().filter(c -> c == ';').count();
        return semicolons > commas ? ';' : ',';
    }

    private String cell(String[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private double optionalCoordinate(String[] row, int idx) {
        String value = cell(row, idx);
        if (value.isBlank()) return 0.0;
        return Double.parseDouble(value);
    }

    private boolean isBlankRow(String[] row) {
        if (row == null) return true;
        for (String s : row) {
            if (s != null && !s.isBlank()) return false;
        }
        return true;
    }

    private int parseExtendedHourMinutes(String rawHour) {
        String value = rawHour == null ? "" : rawHour.trim();
        Matcher matcher = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Hora no valida: " + rawHour);
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour < 0 || hour > 72) {
            throw new IllegalArgumentException("Hora fuera de rango: " + rawHour);
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Minutos fuera de rango: " + rawHour);
        }
        return hour * 60 + minute;
    }

    private List<Path> selectEnviosFiles(List<Path> all,
                                         List<String> includeOrigins,
                                         int seed,
                                         int maxAirports) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        if (includeOrigins != null && !includeOrigins.isEmpty()) {
            Set<String> selected = includeOrigins.stream()
                    .filter(code -> code != null && !code.isBlank())
                    .map(code -> code.trim().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            return all.stream()
                    .filter(path -> {
                        Matcher matcher = ENVIOS_FILE_PATTERN.matcher(path.getFileName().toString());
                        return matcher.matches() && selected.contains(matcher.group(1).toUpperCase(Locale.ROOT));
                    })
                    .limit(maxAirports)
                    .toList();
        }

        List<Path> shuffled = new ArrayList<>(all);
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed));
        return shuffled.stream().limit(maxAirports).toList();
    }

    private String activeAlgorithmName() {
        var config = simulationConfigRepository.findTopByOrderByIdAsc();
        if (config == null) return "Genetic Algorithm";
        return switch (config.getPrimaryAlgorithm()) {
            case SIMULATED_ANNEALING -> "Simulated Annealing";
            case ANT_COLONY -> "Ant Colony Optimization";
            case GENETIC -> "Genetic Algorithm";
        };
    }

    private String generateBufferedCode(long sequenceValue, LocalDateTime registrationDate) {
        LocalDateTime effectiveDate = registrationDate == null ? LocalDateTime.now() : registrationDate;
        return String.format("%09d-%s", Math.max(1L, sequenceValue), effectiveDate.format(CODE_DATE_FMT));
    }

    private DataImportLog saveLog(String fileName, int total, int success, int errors, StringBuilder errDetail) {
        ImportStatus status = errors == 0
                ? ImportStatus.SUCCESS
                : (success == 0 ? ImportStatus.FAILED : ImportStatus.PARTIAL);

        DataImportLog logEntity = DataImportLog.builder()
                .fileName(fileName)
                .totalRows(total)
                .successRows(success)
                .errorRows(errors)
                .status(status)
                .errorDetails(errors > 0 ? errDetail.toString() : null)
                .build();

        return importLogRepository.save(logEntity);
    }

    private String sanitizeLine(String input) {
        return input
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u00A0", " ")
                .trim();
    }

    private String normalizeText(String input) {
        return sanitizeLine(input).replaceAll("\\s+", " ");
    }

    private double dmsToDecimal(int deg, int min, int sec, String hemisphere) {
        double value = deg + (min / 60.0) + (sec / 3600.0);
        String h = hemisphere.toUpperCase(Locale.ROOT);
        if ("S".equals(h) || "W".equals(h)) {
            value = -value;
        }
        return value;
    }

    private record ParsedAirportRow(
            String icao,
            String city,
            String country,
            Continent continent,
            int capacity,
            double latitude,
            double longitude
    ) {}
}
