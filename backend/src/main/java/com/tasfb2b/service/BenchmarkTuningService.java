package com.tasfb2b.service;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import com.tasfb2b.service.algorithm.OptimizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BenchmarkTuningService {

    private static final int SEEDS_PER_SCENARIO = 5;
    private static final int[] SIZES = new int[]{10, 20, 30};
    private static final int MAX_SHIPMENTS_PER_RUN = 8;

    private static final double W_COMPLETED = 0.30;
    private static final double W_AVG_TRANSIT = 0.25;
    private static final double W_DEADLINE_MISS = 0.20;
    private static final double W_REPLAN_SUCCESS = 0.10;
    private static final double W_COST = 0.10;
    private static final double W_SATURATED = 0.05;

    private final ShipmentOrchestratorService shipmentOrchestratorService;
    private final RoutePlannerService routePlannerService;
    private final SimulationRuntimeService simulationRuntimeService;
    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final GeneticAlgorithm geneticAlgorithm;
    private final AntColonyOptimization antColonyOptimization;

    @Transactional
    public BenchmarkSummary runBenchmarkAndTune() {
        List<AlgorithmProfile> profiles = List.of(
                new AlgorithmProfile("GA-P1", "Genetic Algorithm", 55, 24, 0.05, 24, 20, 0.10, 1.0, 2.0),
                new AlgorithmProfile("GA-P2", "Genetic Algorithm", 70, 30, 0.06, 24, 20, 0.10, 1.0, 2.0),
                new AlgorithmProfile("ACO-P1", "Ant Colony Optimization", 60, 24, 0.06, 20, 24, 0.10, 1.1, 2.1),
                new AlgorithmProfile("ACO-P2", "Ant Colony Optimization", 60, 24, 0.06, 24, 30, 0.09, 1.2, 2.2)
        );

        List<BenchmarkRow> allRows = new ArrayList<>();
        ProfileScore best = null;

        for (AlgorithmProfile profile : profiles) {
            tuneAlgorithms(profile);
            List<BenchmarkRow> rows = runProfile(profile);
            allRows.addAll(rows);
            ProfileScore score = scoreProfile(profile, rows);
            if (best == null || score.totalScore() > best.totalScore()) {
                best = score;
            }
        }

        if (best == null) {
            return new BenchmarkSummary(Map.of(), "N/A", 0, List.of(), List.of(), null);
        }

        tuneAlgorithms(best.profile());
        List<ScenarioAggregate> scenarios = aggregateScenarios(best.profile().profileName(), allRows);

        OptimizationResult summaryMetric = OptimizationResult.builder()
                .algorithmName(best.profile().algorithmFamily())
                .completedShipments((int) Math.round(best.completedPct() * best.sampleSize() / 100.0))
                .completedPct(best.completedPct())
                .avgTransitHours(best.avgTransitHours())
                .totalReplanning(best.totalReplanning())
                .operationalCost(best.operationalCost())
                .flightUtilizationPct(best.flightUtilizationPct())
                .saturatedAirports(best.saturatedAirports())
                .collapseReachedAt(null)
                .build();

        return new BenchmarkSummary(
                Map.of(best.profile().algorithmFamily(), summaryMetric),
                best.profile().algorithmFamily(),
                best.sampleSize(),
                scenarios,
                allRows,
                best
        );
    }

    @Transactional
    public int generateDemandFromScenarioRows(List<ScenarioRow> rows) {
        int created = 0;
        for (ScenarioRow row : rows) {
            try {
                Shipment shipment = shipmentOrchestratorService.createAndPlan(new ShipmentCreateDto(
                        row.airlineName(),
                        row.originIcao(),
                        row.destinationIcao(),
                        row.luggageCount(),
                        row.registrationDate(),
                        row.algorithmName()
                ));
                if (shipment.getStatus() != ShipmentStatus.CRITICAL) {
                    created++;
                }
            } catch (Exception ignored) {
            }
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<ScenarioRow> buildDefaultScenarioRows() {
        LocalDateTime base = LocalDate.now().atStartOfDay().plusHours(1);
        List<ScenarioRow> all = new ArrayList<>();
        all.addAll(buildScenarioRows("NORMAL", "LATAM", 18, 10, 24, base, 4, 7, 1));
        all.addAll(buildScenarioRows("PEAK", "IBERIA", 20, 16, 30, base.plusMinutes(10), 2, 4, 2));
        all.addAll(buildScenarioRows("COLLAPSE", "QATAR", 24, 22, 38, base.plusMinutes(20), 1, 3, 3));
        all.addAll(buildScenarioRows("DISRUPTION", "AIR FRANCE", 16, 18, 34, base.plusMinutes(30), 3, 5, 4));
        all.addAll(buildScenarioRows("RECOVERY", "NIPPON", 14, 8, 20, base.plusMinutes(40), 4, 7, 5));
        return all;
    }

    private List<BenchmarkRow> runProfile(AlgorithmProfile profile) {
        List<BenchmarkRow> rows = new ArrayList<>();
        List<String> scenarios = List.of("NORMAL", "PEAK", "COLLAPSE", "DISRUPTION", "RECOVERY");
        LocalDateTime now = LocalDate.now().atStartOfDay().plusHours(1);

        for (String scenario : scenarios) {
            for (int size : SIZES) {
                for (int seed = 1; seed <= SEEDS_PER_SCENARIO; seed++) {
                    rows.add(runSingle(profile, scenario, size, seed, now));
                }
            }
        }
        return rows;
    }

    private BenchmarkRow runSingle(AlgorithmProfile profile,
                                   String scenario,
                                   int size,
                                   int seed,
                                   LocalDateTime baseNow) {
        simulationRuntimeService.resetDemandKeepingNetwork();

        List<ScenarioRow> scenarioRows = buildScenarioRows(
                scenario,
                scenarioAirline(scenario),
                size,
                minLuggageFor(scenario),
                maxLuggageFor(scenario),
                baseNow.plusMinutes(seed * 2L),
                minuteMinFor(scenario),
                minuteMaxFor(scenario),
                seed
        );

        int created = generateBenchmarkShipments(scenarioRows);

        List<Shipment> shipments = shipmentRepository.findAll();
        List<Airport> airports = airportRepository.findAll();
        List<Flight> availableFlights = flightRepository.findFlightsWithAvailableCapacity();

        PlanningMetrics metrics = evaluatePlanningMetrics(profile.algorithmFamily(), shipments, availableFlights, airports);
        double composite = score(
                metrics.completedPct(),
                metrics.avgTransitHours(),
                metrics.deadlineMissRate(),
                metrics.replanSuccessPct(),
                metrics.operationalCost(),
                metrics.saturatedAirports()
        );

        return new BenchmarkRow(
                profile.profileName(),
                profile.algorithmFamily(),
                scenario,
                size,
                seed,
                created,
                0,
                metrics.replanned(),
                metrics.delivered(),
                metrics.completedPct(),
                metrics.avgTransitHours(),
                metrics.deadlineMissRate(),
                metrics.operationalCost(),
                metrics.flightUtilizationPct(),
                metrics.saturatedAirports(),
                metrics.replanSuccessPct(),
                composite
        );
    }

    private PlanningMetrics evaluatePlanningMetrics(String algorithmName,
                                                    List<Shipment> shipments,
                                                    List<Flight> availableFlights,
                                                    List<Airport> airports) {
        if (shipments == null || shipments.isEmpty()) {
            return new PlanningMetrics(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0);
        }

        List<Shipment> sample = shipments.stream()
                .sorted(java.util.Comparator.comparing(Shipment::getRegistrationDate, java.util.Comparator.nullsLast(LocalDateTime::compareTo)))
                .limit(MAX_SHIPMENTS_PER_RUN)
                .toList();

        int total = sample.size();
        int feasible = 0;
        int misses = 0;
        double transitHoursSum = 0.0;
        double operationalCost = 0.0;

        Map<Long, Integer> projectedFlightLoads = new HashMap<>();
        Set<String> usedAirports = new HashSet<>();

        for (Shipment shipment : sample) {
            List<TravelStop> stops = routePlannerService.previewRoute(
                    shipment,
                    algorithmName,
                    availableFlights,
                    airports
            );

            if (stops.isEmpty()) {
                misses++;
                continue;
            }

            double etaHours = computeEtaHours(shipment, stops);
            boolean deadlineMiss = shipment.getDeadline() != null
                    && shipment.getRegistrationDate() != null
                    && shipment.getRegistrationDate().plusMinutes((long) Math.ceil(etaHours * 60.0)).isAfter(shipment.getDeadline());

            feasible++;
            transitHoursSum += etaHours;
            if (deadlineMiss) {
                misses++;
            }

            int hops = Math.max(0, stops.size() - 2);
            int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
            operationalCost += (luggage * 0.22) + (etaHours * 1.4) + (hops * 7.5) + (deadlineMiss ? 65.0 : 0.0);

            for (TravelStop stop : stops) {
                usedAirports.add(stop.getAirport().getIcaoCode());
                if (stop.getFlight() == null) {
                    continue;
                }
                projectedFlightLoads.merge(stop.getFlight().getId(), luggage, Integer::sum);
            }
        }

        double completedPct = total == 0 ? 0.0 : feasible * 100.0 / total;
        double avgTransit = feasible == 0 ? 0.0 : transitHoursSum / feasible;
        double deadlineMissRate = total == 0 ? 0.0 : misses * 100.0 / total;
        double replanSuccessPct = Math.max(0.0, 100.0 - deadlineMissRate);

        Map<Long, Flight> flightById = new HashMap<>();
        for (Flight flight : availableFlights) {
            flightById.put(flight.getId(), flight);
        }

        double utilizationSum = 0.0;
        int utilizedFlights = 0;
        for (Map.Entry<Long, Integer> entry : projectedFlightLoads.entrySet()) {
            Flight flight = flightById.get(entry.getKey());
            if (flight == null || flight.getMaxCapacity() == null || flight.getMaxCapacity() <= 0) {
                continue;
            }
            int projected = (flight.getCurrentLoad() == null ? 0 : flight.getCurrentLoad()) + entry.getValue();
            double pct = Math.min(100.0, projected * 100.0 / flight.getMaxCapacity());
            utilizationSum += pct;
            utilizedFlights++;
        }
        double flightUtilizationPct = utilizedFlights == 0 ? 0.0 : utilizationSum / utilizedFlights;

        Map<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airports) {
            airportByIcao.put(airport.getIcaoCode(), airport);
        }

        int saturated = 0;
        for (String icao : usedAirports) {
            Airport airport = airportByIcao.get(icao);
            if (airport != null && airport.getOccupancyPct() >= 90.0) {
                saturated++;
            }
        }

        return new PlanningMetrics(
                total,
                feasible,
                completedPct,
                avgTransit,
                deadlineMissRate,
                operationalCost,
                flightUtilizationPct,
                saturated,
                Math.max(0, total - feasible)
        );
    }

    private double computeEtaHours(Shipment shipment, List<TravelStop> stops) {
        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        LocalDateTime finalArrival = stops.stream()
                .filter(stop -> stop.getFlight() != null)
                .map(stop -> stop.getFlight().getScheduledArrival())
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(registration);
        return Math.max(0.0, ChronoUnit.MINUTES.between(registration, finalArrival) / 60.0);
    }

    private int generateBenchmarkShipments(List<ScenarioRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        Map<String, Airport> airportByIcao = new HashMap<>();
        for (Airport airport : airportRepository.findAll()) {
            airportByIcao.put(airport.getIcaoCode().toUpperCase(), airport);
        }

        List<Shipment> toSave = new ArrayList<>();
        int sequence = 1;
        int year = LocalDate.now().getYear();

        for (ScenarioRow row : rows) {
            Airport origin = airportByIcao.get(row.originIcao().toUpperCase());
            Airport destination = airportByIcao.get(row.destinationIcao().toUpperCase());
            if (origin == null || destination == null || origin.getId().equals(destination.getId())) {
                continue;
            }

            Shipment shipment = Shipment.builder()
                    .shipmentCode(String.format("BMK-%d-%04d", year, sequence++))
                    .airlineName(row.airlineName())
                    .originAirport(origin)
                    .destinationAirport(destination)
                    .luggageCount(row.luggageCount())
                    .registrationDate(row.registrationDate())
                    .status(ShipmentStatus.PENDING)
                    .progressPercentage(0.0)
                    .build();
            toSave.add(shipment);
        }

        if (toSave.isEmpty()) {
            return 0;
        }

        shipmentRepository.saveAll(toSave);
        return toSave.size();
    }

    private double score(double completedPct,
                         double avgTransit,
                         double deadlineMissRate,
                         double replanSuccessPct,
                         double operationalCost,
                         double saturated) {
        double transitScore = Math.max(0.0, 100.0 - avgTransit * 4.0);
        double deadlineScore = Math.max(0.0, 100.0 - deadlineMissRate);
        double costScore = Math.max(0.0, 100.0 - operationalCost / 25.0);
        double saturatedScore = Math.max(0.0, 100.0 - saturated * 10.0);

        return (W_COMPLETED * completedPct)
                + (W_AVG_TRANSIT * transitScore)
                + (W_DEADLINE_MISS * deadlineScore)
                + (W_REPLAN_SUCCESS * replanSuccessPct)
                + (W_COST * costScore)
                + (W_SATURATED * saturatedScore);
    }

    private ProfileScore scoreProfile(AlgorithmProfile profile, List<BenchmarkRow> rows) {
        int n = rows.size();
        if (n == 0) {
            return new ProfileScore(profile, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0);
        }

        double completedPct = rows.stream().mapToDouble(BenchmarkRow::completedPct).average().orElse(0.0);
        double avgTransit = rows.stream().mapToDouble(BenchmarkRow::avgTransitHours).average().orElse(0.0);
        double deadlineMissRate = rows.stream().mapToDouble(BenchmarkRow::deadlineMissRate).average().orElse(0.0);
        double replanSuccessPct = rows.stream().mapToDouble(BenchmarkRow::replanSuccessPct).average().orElse(0.0);
        double operationalCost = rows.stream().mapToDouble(BenchmarkRow::operationalCost).average().orElse(0.0);
        double flightUtilizationPct = rows.stream().mapToDouble(BenchmarkRow::flightUtilizationPct).average().orElse(0.0);
        int saturatedAirports = (int) Math.round(rows.stream().mapToDouble(BenchmarkRow::saturatedAirports).average().orElse(0.0));
        int totalReplanning = (int) Math.round(rows.stream().mapToDouble(BenchmarkRow::replanned).average().orElse(0.0));
        double totalScore = rows.stream().mapToDouble(BenchmarkRow::compositeScore).average().orElse(0.0);

        return new ProfileScore(
                profile,
                n,
                completedPct,
                avgTransit,
                deadlineMissRate,
                replanSuccessPct,
                operationalCost,
                flightUtilizationPct,
                saturatedAirports,
                totalReplanning,
                totalScore
        );
    }

    private List<ScenarioAggregate> aggregateScenarios(String profileName, List<BenchmarkRow> rows) {
        Map<String, List<BenchmarkRow>> grouped = new LinkedHashMap<>();
        for (BenchmarkRow row : rows) {
            if (!row.profileName().equals(profileName)) {
                continue;
            }
            grouped.computeIfAbsent(row.scenario(), ignored -> new ArrayList<>()).add(row);
        }

        List<ScenarioAggregate> output = new ArrayList<>();
        for (Map.Entry<String, List<BenchmarkRow>> entry : grouped.entrySet()) {
            List<BenchmarkRow> values = entry.getValue();
            output.add(new ScenarioAggregate(
                    entry.getKey(),
                    values.isEmpty() ? "N/A" : values.get(0).algorithm(),
                    (int) Math.round(values.stream().mapToDouble(BenchmarkRow::createdShipments).average().orElse(0.0)),
                    0,
                    0,
                    values.size(),
                    values.stream().mapToDouble(BenchmarkRow::completedPct).average().orElse(0.0),
                    values.stream().mapToDouble(BenchmarkRow::avgTransitHours).average().orElse(0.0),
                    values.stream().mapToDouble(BenchmarkRow::deadlineMissRate).average().orElse(0.0),
                    values.stream().mapToDouble(BenchmarkRow::compositeScore).average().orElse(0.0)
            ));
        }
        return output;
    }

    private List<ScenarioRow> buildScenarioRows(
            String scenario,
            String airline,
            int count,
            int minLuggage,
            int maxLuggage,
            LocalDateTime base,
            int minuteStepMin,
            int minuteStepMax,
            int seed
    ) {
        List<Airport> airports = airportRepository.findAll();
        if (airports.size() < 2) {
            return List.of();
        }

        List<Airport> america = airports.stream().filter(a -> a.getContinent() == Continent.AMERICA).toList();
        List<Airport> europe = airports.stream().filter(a -> a.getContinent() == Continent.EUROPE).toList();
        List<Airport> asia = airports.stream().filter(a -> a.getContinent() == Continent.ASIA).toList();
        List<Airport> all = new ArrayList<>();
        all.addAll(america);
        all.addAll(europe);
        all.addAll(asia);
        if (all.size() < 2) {
            return List.of();
        }

        List<ScenarioRow> rows = new ArrayList<>();
        LocalDateTime cursor = base;
        int seedShift = Math.max(seed, 1);
        int range = Math.max(1, maxLuggage - minLuggage + 1);
        int minuteRange = Math.max(1, minuteStepMax - minuteStepMin + 1);

        for (int i = 0; i < count; i++) {
            int idx = i + seedShift;
            Airport origin = all.get(Math.floorMod(idx * 3 + seedShift, all.size()));
            Airport destination = all.get(Math.floorMod(idx * 7 + 5 + seedShift, all.size()));
            int guard = 0;
            while (destination.getIcaoCode().equalsIgnoreCase(origin.getIcaoCode()) && guard < all.size()) {
                destination = all.get(Math.floorMod(idx * 11 + 9 + guard + seedShift, all.size()));
                guard++;
            }

            int luggage = minLuggage + Math.floorMod(idx, range);
            String algorithm = (idx % 2 == 0) ? "Genetic Algorithm" : "Ant Colony Optimization";
            rows.add(new ScenarioRow(
                    scenario,
                    airline,
                    origin.getIcaoCode(),
                    destination.getIcaoCode(),
                    luggage,
                    cursor,
                    algorithm
            ));

            int step = minuteStepMin + Math.floorMod(idx, minuteRange);
            cursor = cursor.plusMinutes(step);
        }

        return rows;
    }

    private void tuneAlgorithms(AlgorithmProfile profile) {
        geneticAlgorithm.setPopulationSize(profile.gaPopulation());
        geneticAlgorithm.setGenerations(profile.gaGenerations());
        geneticAlgorithm.setMutationRate(profile.gaMutation());

        antColonyOptimization.setNumAnts(profile.acoAnts());
        antColonyOptimization.setIterations(profile.acoIterations());
        antColonyOptimization.setEvaporationRate(profile.acoEvaporation());
        antColonyOptimization.setAlpha(profile.acoAlpha());
        antColonyOptimization.setBeta(profile.acoBeta());
    }

    private String scenarioAirline(String scenario) {
        return switch (scenario) {
            case "PEAK" -> "IBERIA";
            case "COLLAPSE" -> "QATAR";
            case "DISRUPTION" -> "AIR FRANCE";
            case "RECOVERY" -> "NIPPON";
            default -> "LATAM";
        };
    }

    private int minLuggageFor(String scenario) {
        return switch (scenario) {
            case "PEAK" -> 16;
            case "COLLAPSE" -> 22;
            case "DISRUPTION" -> 18;
            case "RECOVERY" -> 8;
            default -> 10;
        };
    }

    private int maxLuggageFor(String scenario) {
        return switch (scenario) {
            case "PEAK" -> 30;
            case "COLLAPSE" -> 38;
            case "DISRUPTION" -> 34;
            case "RECOVERY" -> 20;
            default -> 24;
        };
    }

    private int minuteMinFor(String scenario) {
        return switch (scenario) {
            case "PEAK" -> 2;
            case "COLLAPSE" -> 1;
            case "DISRUPTION" -> 3;
            case "RECOVERY" -> 4;
            default -> 4;
        };
    }

    private int minuteMaxFor(String scenario) {
        return switch (scenario) {
            case "PEAK" -> 4;
            case "COLLAPSE" -> 3;
            case "DISRUPTION" -> 5;
            case "RECOVERY" -> 7;
            default -> 7;
        };
    }

    public record ScenarioRow(
            String scenario,
            String airlineName,
            String originIcao,
            String destinationIcao,
            Integer luggageCount,
            LocalDateTime registrationDate,
            String algorithmName
    ) {}

    public record ScenarioAggregate(
            String scenario,
            String winner,
            int createdShipments,
            int cancelledFlights,
            int replannings,
            int sampleSize,
            double completedPct,
            double avgTransitHours,
            double deadlineMissRate,
            double compositeScore
    ) {}

    public record BenchmarkRow(
            String profileName,
            String algorithm,
            String scenario,
            int demandSize,
            int seed,
            int createdShipments,
            int cancelledFlights,
            int replanned,
            long delivered,
            double completedPct,
            double avgTransitHours,
            double deadlineMissRate,
            double operationalCost,
            double flightUtilizationPct,
            int saturatedAirports,
            double replanSuccessPct,
            double compositeScore
    ) {}

    public record AlgorithmProfile(
            String profileName,
            String algorithmFamily,
            int gaPopulation,
            int gaGenerations,
            double gaMutation,
            int acoAnts,
            int acoIterations,
            double acoEvaporation,
            double acoAlpha,
            double acoBeta
    ) {}

    public record ProfileScore(
            AlgorithmProfile profile,
            int sampleSize,
            double completedPct,
            double avgTransitHours,
            double deadlineMissRate,
            double replanSuccessPct,
            double operationalCost,
            double flightUtilizationPct,
            int saturatedAirports,
            int totalReplanning,
            double totalScore
    ) {}

    public record BenchmarkSummary(
            Map<String, OptimizationResult> results,
            String winner,
            int sampleSize,
            List<ScenarioAggregate> scenarios,
            List<BenchmarkRow> rows,
            ProfileScore bestProfile
    ) {}

    private record PlanningMetrics(
            int total,
            int feasible,
            double completedPct,
            double avgTransitHours,
            double deadlineMissRate,
            double operationalCost,
            double flightUtilizationPct,
            int saturatedAirports,
            int replanned
    ) {
        long delivered() {
            return feasible;
        }

        double replanSuccessPct() {
            return Math.max(0.0, 100.0 - deadlineMissRate);
        }
    }
}
