package com.tasfb2b.service;

import com.tasfb2b.dto.FutureDemandGenerationRequestDto;
import com.tasfb2b.dto.FutureDemandGenerationResultDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.projection.FutureRouteBaselineRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FutureDemandProjectionService {

    private static final LocalDate DEFAULT_PROJECTION_END = LocalDate.of(2030, 12, 31);
    private static final int MAX_PROJECTED_ROWS = 120_000;
    private static final int SAVE_BATCH_SIZE = 8000;

    private final ShipmentRepository shipmentRepository;
    private final AirportRepository airportRepository;
    private final SimulationConfigRepository simulationConfigRepository;
    private final FlightScheduleService flightScheduleService;

    @Transactional
    public FutureDemandGenerationResultDto generate(FutureDemandGenerationRequestDto request) {
        LocalDateTime startedAt = LocalDateTime.now();
        SimulationConfig currentConfig = simulationConfigRepository.findTopByOrderByIdAsc();

        LocalDate historicalFrom = request.historicalFrom();
        LocalDate historicalTo = request.historicalTo();
        LocalDate projectionStart = request.projectionStart();
        LocalDate projectionEnd = request.projectionEnd();
        int seed = request.randomSeed() == null ? 11 : request.randomSeed();
        int noisePct = request.randomNoisePct() == null ? 8 : request.randomNoisePct();

        if (projectionEnd == null && projectionStart != null) {
            projectionEnd = DEFAULT_PROJECTION_END;
        }

        if (historicalFrom == null && currentConfig != null && currentConfig.getProjectedHistoricalFrom() != null) {
            historicalFrom = currentConfig.getProjectedHistoricalFrom();
        }
        if (historicalTo == null && currentConfig != null && currentConfig.getProjectedHistoricalTo() != null) {
            historicalTo = currentConfig.getProjectedHistoricalTo();
        }

        if (historicalFrom == null || historicalTo == null) {
            LocalDateTime minRegistration = shipmentRepository.findMinRegistrationDate();
            LocalDateTime maxRegistration = shipmentRepository.findMaxRegistrationDate();
            if (minRegistration == null || maxRegistration == null) {
                throw new IllegalArgumentException("No existe historica para proyectar");
            }
            if (historicalFrom == null) {
                historicalFrom = minRegistration.toLocalDate();
            }
            if (historicalTo == null) {
                historicalTo = maxRegistration.toLocalDate();
            }
        }

        if (projectionStart == null) {
            projectionStart = historicalTo.plusDays(1);
        }
        if (projectionEnd == null) {
            projectionEnd = DEFAULT_PROJECTION_END;
        }

        if (projectionEnd.isBefore(projectionStart)) {
            throw new IllegalArgumentException("projectionEnd debe ser mayor o igual a projectionStart");
        }
        if (historicalTo.isBefore(historicalFrom)) {
            throw new IllegalArgumentException("historicalTo debe ser mayor o igual a historicalFrom");
        }

        if (currentConfig != null
                && Boolean.TRUE.equals(currentConfig.getProjectedDemandReady())
                && currentConfig.getProjectedTo() != null
                && projectionStart != null
                && projectionEnd != null
                && !projectionEnd.isAfter(currentConfig.getProjectedTo())) {
            return repairProjectedCoverage(historicalFrom, historicalTo, projectionStart, projectionEnd, seed, noisePct, startedAt);
        }

        LocalDateTime historyFromTs = historicalFrom.atStartOfDay();
        LocalDateTime historyToTs = historicalTo.plusDays(1).atStartOfDay();

        List<FutureRouteBaselineRow> baselineRows = shipmentRepository.aggregateFutureBaseline(historyFromTs, historyToTs);
        if (baselineRows.isEmpty()) {
            markProjectedReady(
                    historicalFrom,
                    historicalTo,
                    projectionStart,
                    projectionEnd,
                    startedAt
            );
            return new FutureDemandGenerationResultDto(
                    historicalFrom, historicalTo, projectionStart, projectionEnd,
                    0, 0, noisePct, seed, true, startedAt, LocalDateTime.now()
            );
        }

        Map<RouteKey, long[]> routeWeekdayCounts = new HashMap<>();
        Map<RouteKey, Integer> routeAvgLuggage = new HashMap<>();
        for (FutureRouteBaselineRow row : baselineRows) {
            RouteKey key = new RouteKey(row.getOriginIcao(), row.getDestinationIcao());
            int dowIdx = Math.max(0, Math.min(6, (row.getIsoDow() == null ? 1 : row.getIsoDow()) - 1));
            routeWeekdayCounts.computeIfAbsent(key, ignored -> new long[7])[dowIdx] = row.getShipmentCount() == null ? 0L : row.getShipmentCount();
            int avg = row.getAvgLuggage() == null ? 8 : Math.max(1, (int) Math.round(row.getAvgLuggage()));
            routeAvgLuggage.put(key, avg);
        }

        Map<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airportRepository.findAll()) {
            airportByIcao.put(airport.getIcaoCode().toUpperCase(Locale.ROOT), airport);
        }

        Random random = new Random(seed);
        long monthsSinceHistoryStart = Math.max(1, ChronoUnit.MONTHS.between(historicalFrom.withDayOfMonth(1), projectionStart.withDayOfMonth(1)));
        double baseGrowth = 1.0 + (monthsSinceHistoryStart * 0.03);

        LocalDate cursor = projectionStart;
        long projectedDays = ChronoUnit.DAYS.between(projectionStart, projectionEnd) + 1;
        double rawProjectedTotal = estimateRawProjectedTotal(routeWeekdayCounts, projectionStart, projectionEnd, baseGrowth, projectedDays);
        double globalScale = rawProjectedTotal <= 0.0 ? 1.0 : Math.min(1.0, MAX_PROJECTED_ROWS / rawProjectedTotal);
        int deletedRows = projectionStart.equals(historicalTo.plusDays(1))
                ? (int) shipmentRepository.deleteByRegistrationDateAfter(historicalTo.plusDays(1).atStartOfDay())
                : 0;
        String projectionPrefix = "P" + (System.currentTimeMillis() % 10_000_000L) + "-";
        int generatedRows = 0;

        List<Shipment> batch = new ArrayList<>(SAVE_BATCH_SIZE);

        while (!cursor.isAfter(projectionEnd)) {
            int dayOffset = (int) ChronoUnit.DAYS.between(projectionStart, cursor);
            double growthMultiplier = baseGrowth + Math.max(0, dayOffset) / Math.max(1.0, projectedDays) * 0.6;
            DayOfWeek dow = cursor.getDayOfWeek();
            int dowIdx = dow.getValue() - 1;
            int generatedForDay = 0;

            for (var entry : routeWeekdayCounts.entrySet()) {
                RouteKey route = entry.getKey();
                long baseline = entry.getValue()[dowIdx];
                if (baseline <= 0) {
                    continue;
                }

                double noisy = baseline * growthMultiplier;
                double noise = noisy * (noisePct / 100.0) * (random.nextDouble() - 0.5);
                int expected = (int) Math.max(0, Math.round((noisy + noise) * globalScale));
                if (expected <= 0) {
                    continue;
                }

                Airport origin = airportByIcao.get(route.originIcao);
                Airport destination = airportByIcao.get(route.destinationIcao);
                if (origin == null || destination == null || origin.getId().equals(destination.getId())) {
                    continue;
                }

                int avgLuggage = routeAvgLuggage.getOrDefault(route, 8);
                for (int i = 0; i < expected; i++) {
                    int hour = random.nextInt(24);
                    int minute = random.nextInt(60);
                    LocalDateTime registration = cursor.atTime(hour, minute);
                    int luggage = Math.max(1, avgLuggage + random.nextInt(5) - 2);
                    String airline = pickAirline(route, i);

                    batch.add(Shipment.builder()
                            .shipmentCode(projectionPrefix + generatedRows)
                            .airlineName(airline)
                            .originAirport(origin)
                            .destinationAirport(destination)
                            .luggageCount(luggage)
                            .registrationDate(registration)
                            .status(com.tasfb2b.model.ShipmentStatus.PENDING)
                            .progressPercentage(0.0)
                            .build());

                    generatedRows++;
                    generatedForDay++;
                    if (batch.size() >= SAVE_BATCH_SIZE) {
                        shipmentRepository.saveAll(batch);
                        shipmentRepository.flush();
                        batch.clear();
                    }
                }
            }

            if (generatedForDay == 0) {
                Shipment fallback = buildFallbackShipment(cursor, generatedRows, projectionPrefix, routeWeekdayCounts, routeAvgLuggage, airportByIcao, random);
                if (fallback != null) {
                    batch.add(fallback);
                    generatedRows++;
                    if (batch.size() >= SAVE_BATCH_SIZE) {
                        shipmentRepository.saveAll(batch);
                        shipmentRepository.flush();
                        batch.clear();
                    }
                }
            }

            cursor = cursor.plusDays(1);
        }

        if (!batch.isEmpty()) {
            shipmentRepository.saveAll(batch);
            shipmentRepository.flush();
        }

        markProjectedReady(
                historicalFrom,
                historicalTo,
                projectionStart,
                projectionEnd,
                startedAt
        );
        generatedRows += ensureDailyCoverage(projectionStart, projectionEnd, routeWeekdayCounts, routeAvgLuggage, airportByIcao, seed + 97);
        return new FutureDemandGenerationResultDto(
                historicalFrom,
                historicalTo,
                projectionStart,
                projectionEnd,
                generatedRows,
                deletedRows,
                noisePct,
                seed,
                true,
                startedAt,
                LocalDateTime.now()
        );
    }

    private FutureDemandGenerationResultDto repairProjectedCoverage(
            LocalDate historicalFrom,
            LocalDate historicalTo,
            LocalDate projectionStart,
            LocalDate projectionEnd,
            int seed,
            int noisePct,
            LocalDateTime startedAt
    ) {
        List<FutureRouteBaselineRow> baselineRows = shipmentRepository.aggregateFutureBaseline(
                historicalFrom.atStartOfDay(),
                historicalTo.plusDays(1).atStartOfDay()
        );

        Map<RouteKey, long[]> routeWeekdayCounts = new HashMap<>();
        Map<RouteKey, Integer> routeAvgLuggage = new HashMap<>();
        for (FutureRouteBaselineRow row : baselineRows) {
            RouteKey key = new RouteKey(row.getOriginIcao(), row.getDestinationIcao());
            int dowIdx = Math.max(0, Math.min(6, (row.getIsoDow() == null ? 1 : row.getIsoDow()) - 1));
            routeWeekdayCounts.computeIfAbsent(key, ignored -> new long[7])[dowIdx] = row.getShipmentCount() == null ? 0L : row.getShipmentCount();
            int avg = row.getAvgLuggage() == null ? 8 : Math.max(1, (int) Math.round(row.getAvgLuggage()));
            routeAvgLuggage.put(key, avg);
        }

        Map<String, Airport> airportByIcao = loadAirportsByIcao();
        int inserted = ensureDailyCoverage(projectionStart, projectionEnd, routeWeekdayCounts, routeAvgLuggage, airportByIcao, seed + 97);

        markProjectedReady(historicalFrom, historicalTo, projectionStart, projectionEnd, startedAt);
        return new FutureDemandGenerationResultDto(
                historicalFrom,
                historicalTo,
                projectionStart,
                projectionEnd,
                inserted,
                0,
                noisePct,
                seed,
                true,
                startedAt,
                LocalDateTime.now()
        );
    }

    private void markProjectedReady(
            LocalDate historicalFrom,
            LocalDate historicalTo,
            LocalDate projectionStart,
            LocalDate projectionEnd,
            LocalDateTime generatedAt
    ) {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        if (config == null) {
            config = SimulationConfig.builder().build();
        }

        LocalDate mergedHistoricalFrom = historicalFrom;
        LocalDate mergedHistoricalTo = historicalTo;
        LocalDate mergedProjectedFrom = projectionStart;
        LocalDate mergedProjectedTo = projectionEnd;

        if (Boolean.TRUE.equals(config.getProjectedDemandReady())) {
            if (config.getProjectedHistoricalFrom() != null && (mergedHistoricalFrom == null || config.getProjectedHistoricalFrom().isBefore(mergedHistoricalFrom))) {
                mergedHistoricalFrom = config.getProjectedHistoricalFrom();
            }
            if (config.getProjectedHistoricalTo() != null && (mergedHistoricalTo == null || config.getProjectedHistoricalTo().isBefore(mergedHistoricalTo))) {
                mergedHistoricalTo = config.getProjectedHistoricalTo();
            }
            if (config.getProjectedFrom() != null && (mergedProjectedFrom == null || config.getProjectedFrom().isBefore(mergedProjectedFrom))) {
                mergedProjectedFrom = config.getProjectedFrom();
            }
            if (config.getProjectedTo() != null && (mergedProjectedTo == null || config.getProjectedTo().isAfter(mergedProjectedTo))) {
                mergedProjectedTo = config.getProjectedTo();
            }
        }

        config.setProjectedDemandReady(true);
        config.setProjectedHistoricalFrom(mergedHistoricalFrom);
        config.setProjectedHistoricalTo(mergedHistoricalTo);
        config.setProjectedFrom(mergedProjectedFrom);
        config.setProjectedTo(mergedProjectedTo);
        config.setProjectedGeneratedAt(generatedAt);
        simulationConfigRepository.save(config);
    }

    private String pickAirline(RouteKey route, int index) {
        int hash = Math.abs((route.originIcao + route.destinationIcao + index).hashCode());
        String[] airlines = {"LATAM", "AVIANCA", "IBERIA", "AIR FRANCE", "QATAR", "EMIRATES", "LUFTHANSA", "DELTA"};
        return airlines[hash % airlines.length];
    }

    private record RouteKey(String originIcao, String destinationIcao) {
    }

    private double estimateRawProjectedTotal(
            Map<RouteKey, long[]> routeWeekdayCounts,
            LocalDate projectionStart,
            LocalDate projectionEnd,
            double baseGrowth,
            long projectedDays
    ) {
        double total = 0.0;
        LocalDate cursor = projectionStart;
        while (!cursor.isAfter(projectionEnd)) {
            int dayOffset = (int) ChronoUnit.DAYS.between(projectionStart, cursor);
            double growthMultiplier = baseGrowth + Math.max(0, dayOffset) / Math.max(1.0, projectedDays) * 0.6;
            int dowIdx = cursor.getDayOfWeek().getValue() - 1;
            for (long[] weekday : routeWeekdayCounts.values()) {
                total += weekday[dowIdx] * growthMultiplier;
            }
            cursor = cursor.plusDays(1);
        }
        return total;
    }

    @Transactional
    public FutureDemandGenerationResultDto generateDefaultProjectionNow() {
        FutureDemandGenerationResultDto result = generate(new FutureDemandGenerationRequestDto(
                null,
                null,
                null,
                DEFAULT_PROJECTION_END,
                11,
                8
        ));
        log.info("Demanda futura generada y almacenada: {} filas ({})", result.generatedRows(), result.projectionStart() + " -> " + result.projectionEnd());
        return result;
    }

    @Transactional
    public FutureDemandGenerationResultDto ensureCoverageUntil(LocalDate requiredDay) {
        if (requiredDay == null) {
            return null;
        }

        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        LocalDate currentFrom = config == null ? null : config.getProjectedFrom();
        LocalDate currentTo = config == null ? null : config.getProjectedTo();

        LocalDate targetEnd = requiredDay.isAfter(DEFAULT_PROJECTION_END) ? requiredDay : DEFAULT_PROJECTION_END;

        if (Boolean.TRUE.equals(config != null ? config.getProjectedDemandReady() : Boolean.FALSE)
                && currentFrom != null
                && currentTo != null
                && !requiredDay.isBefore(currentFrom)
                && !requiredDay.isAfter(currentTo)
                && !currentTo.isBefore(DEFAULT_PROJECTION_END)) {
            return null;
        }

        LocalDate projectionStart = currentTo == null
                ? null
                : currentTo.plusDays(1);

        if (projectionStart != null && projectionStart.isAfter(targetEnd)) {
            return null;
        }

        return generate(new FutureDemandGenerationRequestDto(
                config == null ? null : config.getProjectedHistoricalFrom(),
                config == null ? null : config.getProjectedHistoricalTo(),
                projectionStart,
                targetEnd,
                11,
                8
        ));
    }

    @Transactional
    public int repairProjectedDailyCoverage(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0;
        }

        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        Map<RouteKey, long[]> routeWeekdayCounts = loadRouteWeekdayCounts(config);
        Map<RouteKey, Integer> routeAvgLuggage = loadRouteAvgLuggage(config);
        Map<String, Airport> airportByIcao = loadAirportsByIcao();
        int inserted = ensureDailyCoverage(from, to, routeWeekdayCounts, routeAvgLuggage, airportByIcao, 911);

        LocalDate historicalFrom = config == null ? null : config.getProjectedHistoricalFrom();
        LocalDate historicalTo = config == null ? null : config.getProjectedHistoricalTo();
        markProjectedReady(historicalFrom, historicalTo, from, to, LocalDateTime.now());
        return inserted;
    }

    private int ensureDailyCoverage(
            LocalDate from,
            LocalDate to,
            Map<RouteKey, long[]> routeWeekdayCounts,
            Map<RouteKey, Integer> routeAvgLuggage,
            Map<String, Airport> airportByIcao,
            int seed
    ) {
        if (from == null || to == null || to.isBefore(from) || routeWeekdayCounts.isEmpty() || airportByIcao.isEmpty()) {
            return 0;
        }

        Set<LocalDate> existingDays = shipmentRepository.findDistinctRegistrationDatesBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .map(java.sql.Date::toLocalDate)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Random random = new Random(seed);
        List<Shipment> batch = new ArrayList<>(SAVE_BATCH_SIZE);
        int inserted = 0;

        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            if (!existingDays.contains(cursor)) {
                Shipment fallback = buildFallbackShipment(cursor, inserted, "PGAP-", routeWeekdayCounts, routeAvgLuggage, airportByIcao, random);
                if (fallback != null) {
                    batch.add(fallback);
                    inserted++;
                    if (batch.size() >= SAVE_BATCH_SIZE) {
                        shipmentRepository.saveAll(batch);
                        shipmentRepository.flush();
                        batch.clear();
                    }
                }
            }
            cursor = cursor.plusDays(1);
        }

        if (!batch.isEmpty()) {
            shipmentRepository.saveAll(batch);
            shipmentRepository.flush();
        }

        return inserted;
    }

    private Shipment buildFallbackShipment(
            LocalDate day,
            int sequence,
            String prefix,
            Map<RouteKey, long[]> routeWeekdayCounts,
            Map<RouteKey, Integer> routeAvgLuggage,
            Map<String, Airport> airportByIcao,
            Random random
    ) {
        RouteKey route = pickFallbackRoute(day, routeWeekdayCounts, airportByIcao);
        if (route == null) {
            return null;
        }

        Airport origin = airportByIcao.get(route.originIcao);
        Airport destination = airportByIcao.get(route.destinationIcao);
        if (origin == null || destination == null || origin.getId().equals(destination.getId())) {
            return null;
        }

        int avgLuggage = routeAvgLuggage.getOrDefault(route, 8);
        LocalDateTime registration = day.atTime(9 + random.nextInt(8), random.nextInt(60));
        int luggage = Math.max(1, avgLuggage + random.nextInt(3));

        long nextSequence = shipmentRepository.findMaxProjectedSequenceByPrefix(prefix + "%") + sequence + 1;

        return Shipment.builder()
                .shipmentCode(prefix + day + "-" + nextSequence)
                .airlineName(pickAirline(route, sequence))
                .originAirport(origin)
                .destinationAirport(destination)
                .luggageCount(luggage)
                .registrationDate(registration)
                .status(com.tasfb2b.model.ShipmentStatus.PENDING)
                .progressPercentage(0.0)
                .build();
    }

    private RouteKey pickFallbackRoute(LocalDate day,
                                       Map<RouteKey, long[]> routeWeekdayCounts,
                                       Map<String, Airport> airportByIcao) {
        int dowIdx = day.getDayOfWeek().getValue() - 1;
        RouteKey best = null;
        long bestCount = -1L;

        for (var entry : routeWeekdayCounts.entrySet()) {
            Airport origin = airportByIcao.get(entry.getKey().originIcao);
            Airport destination = airportByIcao.get(entry.getKey().destinationIcao);
            if (origin == null || destination == null || origin.getId().equals(destination.getId())) {
                continue;
            }
            long count = entry.getValue()[dowIdx];
            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
            }
        }

        if (best != null) {
            return best;
        }

        return routeWeekdayCounts.keySet().stream()
                .filter(route -> {
                    Airport origin = airportByIcao.get(route.originIcao);
                    Airport destination = airportByIcao.get(route.destinationIcao);
                    return origin != null && destination != null && !origin.getId().equals(destination.getId());
                })
                .findFirst()
                .orElse(null);
    }

    private Map<RouteKey, long[]> loadRouteWeekdayCounts(SimulationConfig config) {
        LocalDate historicalFrom = config == null ? null : config.getProjectedHistoricalFrom();
        LocalDate historicalTo = config == null ? null : config.getProjectedHistoricalTo();
        if (historicalFrom == null || historicalTo == null) {
            LocalDateTime minRegistration = shipmentRepository.findMinRegistrationDate();
            LocalDateTime maxRegistration = shipmentRepository.findMaxRegistrationDate();
            if (minRegistration == null || maxRegistration == null) {
                return Map.of();
            }
            historicalFrom = minRegistration.toLocalDate();
            historicalTo = maxRegistration.toLocalDate();
        }

        List<FutureRouteBaselineRow> baselineRows = shipmentRepository.aggregateFutureBaseline(historicalFrom.atStartOfDay(), historicalTo.plusDays(1).atStartOfDay());
        Map<RouteKey, long[]> routeWeekdayCounts = new HashMap<>();
        for (FutureRouteBaselineRow row : baselineRows) {
            RouteKey key = new RouteKey(row.getOriginIcao(), row.getDestinationIcao());
            int dowIdx = Math.max(0, Math.min(6, (row.getIsoDow() == null ? 1 : row.getIsoDow()) - 1));
            routeWeekdayCounts.computeIfAbsent(key, ignored -> new long[7])[dowIdx] = row.getShipmentCount() == null ? 0L : row.getShipmentCount();
        }
        return routeWeekdayCounts;
    }

    private Map<RouteKey, Integer> loadRouteAvgLuggage(SimulationConfig config) {
        LocalDate historicalFrom = config == null ? null : config.getProjectedHistoricalFrom();
        LocalDate historicalTo = config == null ? null : config.getProjectedHistoricalTo();
        if (historicalFrom == null || historicalTo == null) {
            LocalDateTime minRegistration = shipmentRepository.findMinRegistrationDate();
            LocalDateTime maxRegistration = shipmentRepository.findMaxRegistrationDate();
            if (minRegistration == null || maxRegistration == null) {
                return Map.of();
            }
            historicalFrom = minRegistration.toLocalDate();
            historicalTo = maxRegistration.toLocalDate();
        }

        List<FutureRouteBaselineRow> baselineRows = shipmentRepository.aggregateFutureBaseline(historicalFrom.atStartOfDay(), historicalTo.plusDays(1).atStartOfDay());
        Map<RouteKey, Integer> routeAvgLuggage = new HashMap<>();
        for (FutureRouteBaselineRow row : baselineRows) {
            RouteKey key = new RouteKey(row.getOriginIcao(), row.getDestinationIcao());
            int avg = row.getAvgLuggage() == null ? 8 : Math.max(1, (int) Math.round(row.getAvgLuggage()));
            routeAvgLuggage.put(key, avg);
        }
        return routeAvgLuggage;
    }

    private Map<String, Airport> loadAirportsByIcao() {
        Map<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airportRepository.findAll()) {
            airportByIcao.put(airport.getIcaoCode().toUpperCase(Locale.ROOT), airport);
        }
        return airportByIcao;
    }
}
