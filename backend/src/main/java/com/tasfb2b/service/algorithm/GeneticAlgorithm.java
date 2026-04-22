package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service("geneticAlgorithm")
public class GeneticAlgorithm implements RouteOptimizer {

    private int populationSize = 100;
    private int generations = 50;
    private double mutationRate = 0.1;

    private record Individual(List<Flight> route, double fitness) {
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
        List<List<Flight>> candidates = RoutePlanningSupport.enumerateRoutes(shipment, availableFlights);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Random random = randomForShipment(shipment, 17L);
        List<Individual> population = initializePopulation(shipment, candidates, random);
        if (population.isEmpty()) {
            return List.of();
        }

        for (int generation = 0; generation < generations; generation++) {
            population = evolve(population, shipment, candidates, random);
        }

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
        Map<String, Individual> population = new LinkedHashMap<>();
        List<List<Flight>> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(route -> RoutePlanningSupport.routeScore(shipment, route)))
                .toList();

        for (List<Flight> candidate : sorted) {
            addIndividual(population, shipment, candidate);
            if (population.size() >= populationSize) {
                return new ArrayList<>(population.values());
            }
        }

        int attempts = 0;
        int maxAttempts = Math.max(populationSize * 6, sorted.size() * 4);
        while (population.size() < populationSize && !sorted.isEmpty() && attempts++ < maxAttempts) {
            List<Flight> seed = sorted.get(random.nextInt(sorted.size()));
            List<Flight> mutated = mutateRoute(seed, candidates, random);
            addIndividual(population, shipment, mutated);
            if (population.size() < populationSize) {
                addIndividual(population, shipment, seed);
            }
        }

        return new ArrayList<>(population.values());
    }

    List<Individual> evolve(List<Individual> population,
                            Shipment shipment,
                            List<List<Flight>> candidates,
                            Random random) {
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
        int maxAttempts = Math.max(populationSize * 8, ranked.size() * 6);
        while (next.size() < populationSize && attempts++ < maxAttempts) {
            Individual parentA = selectParent(ranked, random);
            Individual parentB = selectParent(ranked, random);
            List<Flight> childRoute = crossoverRoutes(parentA.route(), parentB.route(), candidates, random);
            if (random.nextDouble() < mutationRate) {
                childRoute = mutateRoute(childRoute, candidates, random);
            }
            addIndividual(next, shipment, childRoute);
        }

        return next.values().stream()
                .sorted(Comparator.comparingDouble(Individual::fitness).reversed())
                .limit(populationSize)
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

    List<Flight> crossoverRoutes(List<Flight> parentA,
                                 List<Flight> parentB,
                                 List<List<Flight>> candidates,
                                 Random random) {
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

        return bestRoute(pool, parentA, random);
    }

    List<Flight> mutateRoute(List<Flight> route,
                             List<List<Flight>> candidates,
                             Random random) {
        if (candidates.isEmpty()) {
            return route;
        }

        List<List<Flight>> pool = new ArrayList<>();
        pool.add(route);
        int sample = Math.min(8, candidates.size());
        for (int i = 0; i < sample; i++) {
            pool.add(candidates.get(random.nextInt(candidates.size())));
        }

        return bestRoute(pool, route, random);
    }

    double fitness(List<Flight> route, Shipment shipment) {
        return RoutePlanningSupport.routeFitness(shipment, route);
    }

    private List<Flight> bestRoute(List<List<Flight>> routes, List<Flight> fallback, Random random) {
        List<List<Flight>> viable = routes.stream()
                .filter(route -> route != null && !route.isEmpty())
                .toList();
        if (viable.isEmpty()) {
            return fallback;
        }
        int pickWindow = Math.min(3, viable.size());
        return viable.get(random.nextInt(pickWindow));
    }

    private void addIndividual(Map<String, Individual> population, Shipment shipment, List<Flight> route) {
        if (!RoutePlanningSupport.isFeasibleRoute(shipment, route)) {
            return;
        }
        List<Flight> normalized = RoutePlanningSupport.normalizeRoute(route);
        String key = routeKey(normalized);
        double fitness = fitness(normalized, shipment);
        Individual current = population.get(key);
        if (current == null || fitness > current.fitness()) {
            population.put(key, new Individual(normalized, fitness));
        }
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
