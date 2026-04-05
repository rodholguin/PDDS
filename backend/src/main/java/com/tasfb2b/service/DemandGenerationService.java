package com.tasfb2b.service;

import com.tasfb2b.dto.DemandGenerationRequestDto;
import com.tasfb2b.dto.DemandGenerationResultDto;
import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class DemandGenerationService {

    private static final List<String> AIRLINES = List.of(
            "LATAM", "AVIANCA", "IBERIA", "AIR FRANCE", "QATAR", "EMIRATES", "LUFTHANSA", "DELTA"
    );

    private final com.tasfb2b.repository.AirportRepository airportRepository;
    private final ShipmentOrchestratorService shipmentOrchestratorService;

    @Transactional
    public DemandGenerationResultDto generate(DemandGenerationRequestDto request) {
        LocalDateTime startedAt = LocalDateTime.now();
        int requested = request.size() == null ? 1000 : request.size();
        int seed = request.seed() == null ? 1 : request.seed();
        int startHour = request.startHour() == null ? 1 : request.startHour();
        String scenario = normalizeScenario(request.scenario());
        String forcedAlgorithm = request.algorithmName();

        List<Airport> airports = airportRepository.findAll();
        if (airports.size() < 2) {
            return new DemandGenerationResultDto(scenario, requested, 0, requested, seed, startedAt, LocalDateTime.now());
        }

        List<Airport> america = airports.stream().filter(a -> a.getContinent() == Continent.AMERICA).toList();
        List<Airport> europe = airports.stream().filter(a -> a.getContinent() == Continent.EUROPE).toList();
        List<Airport> asia = airports.stream().filter(a -> a.getContinent() == Continent.ASIA).toList();
        List<Airport> all = new ArrayList<>(airports);

        Random random = new Random(seed);
        LocalDateTime cursor = LocalDate.now().atStartOfDay().plusHours(startHour);

        int created = 0;
        int failed = 0;
        for (int i = 0; i < requested; i++) {
            Airport origin = pickOriginByScenario(scenario, random, america, europe, asia, all);
            Airport destination = pickDestinationByScenario(scenario, random, origin, america, europe, asia, all);
            if (origin == null || destination == null || origin.getId().equals(destination.getId())) {
                failed++;
                continue;
            }

            int luggage = sampleLuggageByScenario(scenario, random);
            String airline = AIRLINES.get(Math.floorMod(i + seed, AIRLINES.size()));
            String algorithm = forcedAlgorithm == null || forcedAlgorithm.isBlank()
                    ? ((i + seed) % 2 == 0 ? "Genetic Algorithm" : "Ant Colony Optimization")
                    : forcedAlgorithm;

            try {
                shipmentOrchestratorService.createAndPlan(new ShipmentCreateDto(
                        airline,
                        origin.getIcaoCode(),
                        destination.getIcaoCode(),
                        luggage,
                        cursor,
                        algorithm
                ));
                created++;
            } catch (Exception ex) {
                failed++;
            }

            cursor = cursor.plusMinutes(sampleMinuteStepByScenario(scenario, random));
        }

        return new DemandGenerationResultDto(
                scenario,
                requested,
                created,
                failed,
                seed,
                startedAt,
                LocalDateTime.now()
        );
    }

    private String normalizeScenario(String raw) {
        String s = raw == null ? "NORMAL" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "NORMAL", "PEAK", "COLLAPSE", "DISRUPTION", "RECOVERY" -> s;
            default -> "NORMAL";
        };
    }

    private Airport pickOriginByScenario(String scenario,
                                         Random random,
                                         List<Airport> america,
                                         List<Airport> europe,
                                         List<Airport> asia,
                                         List<Airport> all) {
        return switch (scenario) {
            case "COLLAPSE" -> pickFromWeighted(random, List.of(america, europe, asia), List.of(50, 25, 25), all);
            case "PEAK" -> pickFromWeighted(random, List.of(america, europe, asia), List.of(40, 30, 30), all);
            case "DISRUPTION" -> pickFromWeighted(random, List.of(america, europe, asia), List.of(35, 45, 20), all);
            case "RECOVERY" -> pickFromWeighted(random, List.of(america, europe, asia), List.of(34, 33, 33), all);
            default -> all.get(random.nextInt(all.size()));
        };
    }

    private Airport pickDestinationByScenario(String scenario,
                                              Random random,
                                              Airport origin,
                                              List<Airport> america,
                                              List<Airport> europe,
                                              List<Airport> asia,
                                              List<Airport> all) {
        List<Airport> preferred;
        if ("COLLAPSE".equals(scenario) || "PEAK".equals(scenario)) {
            preferred = all.stream()
                    .filter(a -> a.getContinent() == origin.getContinent())
                    .toList();
        } else {
            preferred = all;
        }

        if (preferred.isEmpty()) {
            preferred = all;
        }

        for (int i = 0; i < 20; i++) {
            Airport dst = preferred.get(random.nextInt(preferred.size()));
            if (!dst.getId().equals(origin.getId())) {
                return dst;
            }
        }

        return all.stream().filter(a -> !a.getId().equals(origin.getId())).findFirst().orElse(null);
    }

    private Airport pickFromWeighted(Random random,
                                     List<List<Airport>> groups,
                                     List<Integer> weights,
                                     List<Airport> fallback) {
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(Math.max(1, total));
        int acc = 0;
        for (int i = 0; i < groups.size(); i++) {
            acc += weights.get(i);
            if (roll < acc) {
                List<Airport> group = groups.get(i);
                if (!group.isEmpty()) {
                    return group.get(random.nextInt(group.size()));
                }
            }
        }
        return fallback.get(random.nextInt(fallback.size()));
    }

    private int sampleLuggageByScenario(String scenario, Random random) {
        return switch (scenario) {
            case "PEAK" -> 15 + random.nextInt(22);
            case "COLLAPSE" -> 24 + random.nextInt(32);
            case "DISRUPTION" -> 12 + random.nextInt(20);
            case "RECOVERY" -> 6 + random.nextInt(14);
            default -> 8 + random.nextInt(18);
        };
    }

    private int sampleMinuteStepByScenario(String scenario, Random random) {
        return switch (scenario) {
            case "PEAK" -> 1 + random.nextInt(3);
            case "COLLAPSE" -> 1 + random.nextInt(2);
            case "DISRUPTION" -> 2 + random.nextInt(4);
            case "RECOVERY" -> 3 + random.nextInt(5);
            default -> 2 + random.nextInt(5);
        };
    }
}
