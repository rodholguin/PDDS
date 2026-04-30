package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service("geneticAlgorithm")
public class GeneticAlgorithm implements RouteOptimizer {

    private static final Logger log = LoggerFactory.getLogger(GeneticAlgorithm.class);

    private static final int STAGNATION_LIMIT = 4;
    private static final int ROUTE_PICK_WINDOW = 5;

    private int populationSize = 100;
    private int generations = 50;
    private double mutationRate = 0.1;

    private record Individual(List<Flight> route, double fitness) {
    }

    private record AdaptiveProfile(int populationSize, int generations, double mutationRate) {
    }

    private record RouteEvaluation(List<Flight> route, boolean feasible, double score, double fitness) {
    }

    public void setPopulationSize(int populationSize) {
        this.populationSize = Math.max(20, populationSize);
    }

    public void setGenerations(int generations) {
        this.generations = Math.max(10, generations);
    }

    public void setMutationRate(double mutationRate) {
        this.mutationRate = Math.max(0.01, Math.min(0.5, mutationRate));
    }

    @Override
    public String getAlgorithmName() {
        return "Genetic Algorithm";
    }

    @Override
    public List<TravelStop> planRoute(Shipment shipment,
                                      List<Flight> availableFlights,
                                      List<Airport> airports) {
        return planRouteFromCandidates(shipment, RoutePlanningSupport.planningCandidates(shipment, availableFlights), airports);
    }

    @Override
    public List<TravelStop> planRouteFromCandidates(Shipment shipment,
                                                    List<Flight> candidateFlights,
                                                    List<Airport> airports) {
        List<List<Flight>> candidates = RoutePlanningSupport.enumerateRoutesFromCandidates(shipment, candidateFlights);
        if (candidates.isEmpty()) {
            logGaRun(shipment, candidateFlights.size(), 0, null, 0, true, 0.0);
            return List.of();
        }

        Random random = randomForShipment(shipment, 17L);
        Map<String, RouteEvaluation> evaluationCache = new HashMap<>();
        AdaptiveProfile profile = adaptiveProfile(shipment, candidates, evaluationCache);
        List<Individual> population = initializePopulation(shipment, candidates, random, evaluationCache, profile.populationSize());
        if (population.isEmpty()) {
            logGaRun(shipment, candidateFlights.size(), candidates.size(), profile, 0, true, 0.0);
            return List.of();
        }

        double bestFitness = population.stream().mapToDouble(Individual::fitness).max().orElse(0.0);
        int stagnantGenerations = 0;
        int executedGenerations = 0;
        boolean earlyExit = false;
        for (int generation = 0; generation < profile.generations(); generation++) {
            executedGenerations = generation + 1;
            population = evolve(population, shipment, candidates, random, evaluationCache, profile);
            double generationBest = population.stream().mapToDouble(Individual::fitness).max().orElse(0.0);
            if (generationBest > bestFitness) {
                bestFitness = generationBest;
                stagnantGenerations = 0;
            } else if (++stagnantGenerations >= STAGNATION_LIMIT) {
                earlyExit = true;
                break;
            }
        }

        logGaRun(shipment, candidateFlights.size(), candidates.size(), profile, executedGenerations, earlyExit, bestFitness);

        return population.stream()
                .max(Comparator.comparingDouble(Individual::fitness))
                .map(individual -> RoutePlanningSupport.toTravelStops(shipment, individual.route()))
                .orElse(List.of());
    }

    @Override
    public List<TravelStop> replanRoute(Shipment shipment,
                                        TravelStop failedStop,
                                        List<Flight> availableFlights) {
        Shipment partial = Shipment.builder()
                .shipmentCode((shipment.getShipmentCode() == null ? "GA" : shipment.getShipmentCode()) + "-R")
                .airlineName(shipment.getAirlineName())
                .originAirport(failedStop.getAirport())
                .destinationAirport(shipment.getDestinationAirport())
                .luggageCount(shipment.getLuggageCount())
                .registrationDate(failedStop.getActualArrival() != null ? failedStop.getActualArrival() : LocalDateTime.now())
                .deadline(shipment.getDeadline())
                .isInterContinental(!failedStop.getAirport().getContinent().equals(shipment.getDestinationAirport().getContinent()))
                .build();

        List<Airport> airports = availableFlights.stream()
                .flatMap(flight -> List.of(flight.getOriginAirport(), flight.getDestinationAirport()).stream())
                .distinct()
                .toList();
        List<TravelStop> replanned = planRoute(partial, availableFlights, airports);
        int baseOrder = failedStop.getStopOrder();
        replanned.forEach(stop -> stop.setStopOrder(baseOrder + stop.getStopOrder()));
        return replanned;
    }

    @Override
    public OptimizationResult evaluatePerformance(List<Shipment> shipments,
                                                  LocalDate from,
                                                  LocalDate to) {
        List<Shipment> filtered = shipments.stream()
                .filter(shipment -> withinPeriod(shipment, from, to))
                .toList();

        int total = filtered.size();
        int completed = 0;
        double transitHoursSum = 0.0;
        double operationalCost = 0.0;
        int saturated = 0;
        double utilization = 0.0;
        LocalDateTime collapseReachedAt = null;

        for (Shipment shipment : filtered) {
            boolean delivered = shipment.getStatus() == ShipmentStatus.DELIVERED;
            if (delivered) {
                completed++;
                if (shipment.getDeliveredAt() != null && shipment.getRegistrationDate() != null) {
                    transitHoursSum += Math.max(0, ChronoUnit.HOURS.between(shipment.getRegistrationDate(), shipment.getDeliveredAt()));
                }
            }

            double urgencyPenalty = shipment.getDeadline() == null ? 24.0
                    : Math.max(1.0, ChronoUnit.HOURS.between(LocalDateTime.now(), shipment.getDeadline()));
            operationalCost += shipment.getLuggageCount() * 0.28 + urgencyPenalty * 0.4;
            utilization += shipment.getProgressPercentage() * 0.72;
            if (shipment.getStatus() == ShipmentStatus.CRITICAL || shipment.getStatus() == ShipmentStatus.DELAYED) {
                saturated++;
                if (collapseReachedAt == null) {
                    collapseReachedAt = shipment.getRegistrationDate() != null ? shipment.getRegistrationDate() : LocalDateTime.now();
                }
            }
        }

        long missed = filtered.stream()
                .filter(shipment -> shipment.getStatus() != ShipmentStatus.DELIVERED || !shipment.isDeliveredOnTime())
                .count();
        double deadlineMissRate = total == 0 ? 0.0 : (missed * 100.0 / total);
        double collapsePenalty = collapseReachedAt == null ? 0.0 : Math.max(0.0, 100.0 - deadlineMissRate);
        double collapseDelayScore = (total == 0 ? 0.0 : ((total - saturated) * 100.0 / total));
        double weightedCompleted = (total == 0 ? 0.0 : completed * 100.0 / total);

        return OptimizationResult.builder()
                .algorithmName(getAlgorithmName())
                .completedShipments(completed)
                .completedPct((weightedCompleted * 0.55) + (collapseDelayScore * 0.35) + (collapsePenalty * 0.10))
                .avgTransitHours(completed == 0 ? 0.0 : transitHoursSum / completed)
                .totalReplanning((int) filtered.stream().filter(s -> s.getStatus() == ShipmentStatus.DELAYED).count())
                .operationalCost(total == 0 ? 0.0 : operationalCost)
                .flightUtilizationPct(total == 0 ? 0.0 : utilization / total)
                .saturatedAirports(saturated)
                .collapseReachedAt(collapseReachedAt)
                .build();
    }

    List<Individual> initializePopulation(Shipment shipment, List<List<Flight>> candidates, Random random) {
        return initializePopulation(shipment, candidates, random, new HashMap<>(), populationSize);
    }

    List<Individual> initializePopulation(Shipment shipment,
                                          List<List<Flight>> candidates,
                                          Random random,
                                          Map<String, RouteEvaluation> evaluationCache,
                                          int targetPopulationSize) {
        Map<String, Individual> population = new LinkedHashMap<>();
        List<List<Flight>> sorted = candidates;

        for (List<Flight> candidate : sorted) {
            addIndividual(population, shipment, candidate, evaluationCache);
            if (population.size() >= targetPopulationSize) {
                return new ArrayList<>(population.values());
            }
        }

        int attempts = 0;
        int maxAttempts = Math.max(targetPopulationSize * 6, 240);
        while (population.size() < targetPopulationSize && !sorted.isEmpty() && attempts++ < maxAttempts) {
            List<Flight> seed = sorted.get(random.nextInt(sorted.size()));
            List<Flight> mutated = mutateRoute(shipment, seed, candidates, random, evaluationCache);
            addIndividual(population, shipment, mutated, evaluationCache);
            if (population.size() < targetPopulationSize) {
                addIndividual(population, shipment, seed, evaluationCache);
            }
        }

        return new ArrayList<>(population.values());
    }

    List<Individual> evolve(List<Individual> population,
                            Shipment shipment,
                            List<List<Flight>> candidates,
                            Random random,
                            Map<String, RouteEvaluation> evaluationCache,
                            AdaptiveProfile profile) {
        if (population.isEmpty()) {
            return population;
        }

        List<Individual> ranked = population.stream()
                .sorted(Comparator.comparingDouble(Individual::fitness).reversed())
                .toList();
        int eliteCount = Math.max(1, (int) Math.ceil(ranked.size() * 0.12));
        Map<String, Individual> next = new LinkedHashMap<>();
        for (int i = 0; i < eliteCount; i++) {
            Individual elite = ranked.get(i);
            next.put(routeKey(elite.route()), elite);
        }

        int attempts = 0;
        int maxAttempts = Math.max(profile.populationSize() * 8, 320);
        while (next.size() < profile.populationSize() && attempts++ < maxAttempts) {
            Individual parentA = selectParent(ranked, random);
            Individual parentB = selectParent(ranked, random);
            List<Flight> childRoute = crossoverRoutes(shipment, parentA.route(), parentB.route(), candidates, random, evaluationCache);
            if (random.nextDouble() < profile.mutationRate()) {
                childRoute = mutateRoute(shipment, childRoute, candidates, random, evaluationCache);
            }
            addIndividual(next, shipment, childRoute, evaluationCache);
        }

        return next.values().stream()
                .sorted(Comparator.comparingDouble(Individual::fitness).reversed())
                .limit(profile.populationSize())
                .toList();
    }

    Individual selectParent(List<Individual> population, Random random) {
        Individual best = null;
        for (int i = 0; i < 3; i++) {
            Individual candidate = population.get(random.nextInt(population.size()));
            if (best == null || candidate.fitness() > best.fitness()) {
                best = candidate;
            }
        }
        return best;
    }

    List<Flight> crossoverRoutes(Shipment shipment,
                                 List<Flight> parentA,
                                  List<Flight> parentB,
                                  List<List<Flight>> candidates,
                                  Random random,
                                  Map<String, RouteEvaluation> evaluationCache) {
        List<List<Flight>> pool = new ArrayList<>();
        pool.add(parentA);
        pool.add(parentB);

        if (parentA.size() > 1 && parentB.size() > 1) {
            Long pivotAirport = parentA.get(0).getDestinationAirport().getId();
            for (List<Flight> candidate : candidates) {
                if (candidate.isEmpty()) continue;
                if (candidate.get(0).getOriginAirport().getId().equals(parentA.get(0).getOriginAirport().getId())) {
                    pool.add(candidate);
                }
                boolean startsAtPivot = candidate.stream().anyMatch(flight -> flight.getOriginAirport().getId().equals(pivotAirport));
                if (startsAtPivot) {
                    pool.add(candidate);
                }
            }
        }

        return bestRoute(shipment, pool, parentA, random, evaluationCache);
    }

    List<Flight> mutateRoute(Shipment shipment,
                             List<Flight> route,
                              List<List<Flight>> candidates,
                              Random random,
                              Map<String, RouteEvaluation> evaluationCache) {
        if (candidates.isEmpty()) {
            return route;
        }

        List<List<Flight>> pool = new ArrayList<>();
        pool.add(route);
        int sample = Math.min(8, candidates.size());
        for (int i = 0; i < sample; i++) {
            pool.add(candidates.get(random.nextInt(candidates.size())));
        }

        return bestRoute(shipment, pool, route, random, evaluationCache);
    }

    double fitness(List<Flight> route, Shipment shipment) {
        return evaluateRoute(shipment, route, new HashMap<>()).fitness();
    }

    private List<Flight> bestRoute(Shipment shipment,
                                   List<List<Flight>> routes,
                                   List<Flight> fallback,
                                   Random random,
                                   Map<String, RouteEvaluation> evaluationCache) {
        List<List<Flight>> viable = routes.stream()
                .filter(route -> route != null && !route.isEmpty())
                .toList();
        if (viable.isEmpty()) {
            return fallback;
        }

        List<RouteEvaluation> ranked = viable.stream()
                .map(route -> evaluateRoute(shipment, route, evaluationCache))
                .filter(RouteEvaluation::feasible)
                .sorted(Comparator.comparingDouble(RouteEvaluation::fitness).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return fallback;
        }

        int pickWindow = Math.min(ROUTE_PICK_WINDOW, ranked.size());
        return ranked.get(random.nextInt(pickWindow)).route();
    }

    private void addIndividual(Map<String, Individual> population,
                               Shipment shipment,
                               List<Flight> route,
                               Map<String, RouteEvaluation> evaluationCache) {
        RouteEvaluation evaluation = evaluateRoute(shipment, route, evaluationCache);
        if (!evaluation.feasible()) {
            return;
        }
        String key = routeKey(evaluation.route());
        Individual current = population.get(key);
        if (current == null || evaluation.fitness() > current.fitness()) {
            population.put(key, new Individual(evaluation.route(), evaluation.fitness()));
        }
    }

    private RouteEvaluation evaluateRoute(Shipment shipment,
                                          List<Flight> route,
                                          Map<String, RouteEvaluation> evaluationCache) {
        List<Flight> normalized = RoutePlanningSupport.normalizeRoute(route);
        String key = routeKey(normalized);
        RouteEvaluation cached = evaluationCache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean feasible = RoutePlanningSupport.isFeasibleRoute(shipment, normalized);
        double score = feasible ? RoutePlanningSupport.routeScoreForFeasibleRoute(shipment, normalized) : Double.POSITIVE_INFINITY;
        double fitness = feasible ? RoutePlanningSupport.routeFitnessForScore(score) : 0.0;
        RouteEvaluation evaluation = new RouteEvaluation(normalized, feasible, score, fitness);
        evaluationCache.put(key, evaluation);
        return evaluation;
    }

    private AdaptiveProfile adaptiveProfile(Shipment shipment,
                                            List<List<Flight>> candidates,
                                            Map<String, RouteEvaluation> evaluationCache) {
        int candidateCount = candidates.size();
        int boundedPopulation = Math.min(60, Math.max(20, populationSize));
        int boundedGenerations = Math.min(20, Math.max(8, generations));
        double boundedMutation = Math.max(0.02, Math.min(0.12, mutationRate));

        boolean strongDirect = candidates.stream()
                .filter(route -> route.size() == 1)
                .map(route -> evaluateRoute(shipment, route, evaluationCache))
                .anyMatch(evaluation -> isStrongDirectRoute(shipment, evaluation));

        if (strongDirect || candidateCount <= 12) {
            return new AdaptiveProfile(Math.min(24, boundedPopulation), Math.min(8, boundedGenerations), Math.min(0.04, boundedMutation));
        }
        if (candidateCount <= 32) {
            return new AdaptiveProfile(Math.min(36, boundedPopulation), Math.min(12, boundedGenerations), boundedMutation);
        }
        return new AdaptiveProfile(boundedPopulation, boundedGenerations, boundedMutation);
    }

    private boolean isStrongDirectRoute(Shipment shipment, RouteEvaluation evaluation) {
        if (evaluation == null || !evaluation.feasible() || evaluation.route().size() != 1) {
            return false;
        }
        Flight flight = evaluation.route().get(0);
        if (flight == null || flight.getScheduledArrival() == null) {
            return false;
        }
        boolean withinDeadline = shipment == null || shipment.getDeadline() == null
                || !flight.getScheduledArrival().isAfter(shipment.getDeadline());
        boolean lowFlightLoad = flight.getLoadPct() < 70.0 && flight.getReservedLoadPct() < 85.0;
        boolean lowNodePressure = flight.getDestinationAirport() == null || flight.getDestinationAirport().getOccupancyPct() < 75.0;
        return withinDeadline && lowFlightLoad && lowNodePressure;
    }

    private void logGaRun(Shipment shipment,
                          int candidateFlights,
                          int enumeratedRoutes,
                          AdaptiveProfile profile,
                          int executedGenerations,
                          boolean earlyExit,
                          double bestFitness) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String code = shipment == null || shipment.getShipmentCode() == null ? "N/A" : shipment.getShipmentCode();
        String profileLabel = profile == null
                ? "n/a"
                : profile.populationSize() + "/" + profile.generations() + "/" + profile.mutationRate();
        log.debug(
                "GA shipment={} candidateFlights={} enumeratedRoutes={} profile={} executedGenerations={} earlyExit={} bestFitness={}",
                code,
                candidateFlights,
                enumeratedRoutes,
                profileLabel,
                executedGenerations,
                earlyExit,
                String.format("%.2f", bestFitness)
        );
    }

    private String routeKey(List<Flight> route) {
        return route.stream().map(Flight::getId).map(String::valueOf).reduce((a, b) -> a + "-" + b).orElse("EMPTY");
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        if (shipment.getRegistrationDate() == null) return true;
        LocalDate day = shipment.getRegistrationDate().toLocalDate();
        boolean afterFrom = from == null || !day.isBefore(from);
        boolean beforeTo = to == null || !day.isAfter(to);
        return afterFrom && beforeTo;
    }

    private Random randomForShipment(Shipment shipment, long salt) {
        long base = shipment.getShipmentCode() == null ? salt : shipment.getShipmentCode().hashCode();
        return new Random(base * 31L + salt);
    }
}
