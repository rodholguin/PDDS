package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service("antColonyOptimization")
@Slf4j
public class AntColonyOptimization implements RouteOptimizer {

    private int numAnts = 50;
    private int iterations = 100;
    private double evaporationRate = 0.1;
    private double alpha = 1.0;
    private double beta = 2.0;
    private double initialPheromone = 1.0;

    private final Map<Long, Double> pheromones = new HashMap<>();

    public void setNumAnts(int numAnts) {
        this.numAnts = Math.max(20, numAnts);
    }

    public void setIterations(int iterations) {
        this.iterations = Math.max(30, iterations);
    }

    public void setEvaporationRate(double evaporationRate) {
        this.evaporationRate = Math.max(0.02, Math.min(0.4, evaporationRate));
    }

    public void setAlpha(double alpha) {
        this.alpha = Math.max(0.5, Math.min(3.0, alpha));
    }

    public void setBeta(double beta) {
        this.beta = Math.max(1.0, Math.min(5.0, beta));
    }

    @Override
    public String getAlgorithmName() {
        return "Ant Colony Optimization";
    }

    @Override
    public List<TravelStop> planRoute(Shipment shipment,
                                      List<Flight> availableFlights,
                                      List<Airport> airports) {
        List<Flight> candidateFlights = RoutePlanningSupport.candidateFlights(shipment, availableFlights);
        if (candidateFlights.isEmpty()) {
            return List.of();
        }

        initialize(candidateFlights);
        Random random = randomForShipment(shipment);

        List<Flight> bestRoute = List.of();
        double bestScore = Double.POSITIVE_INFINITY;

        for (int iteration = 0; iteration < iterations; iteration++) {
            List<List<Flight>> antRoutes = new ArrayList<>();
            for (int ant = 0; ant < numAnts; ant++) {
                List<Flight> route = buildSolution(shipment, candidateFlights, random);
                if (!route.isEmpty()) {
                    antRoutes.add(route);
                    double score = RoutePlanningSupport.routeScore(shipment, route);
                    if (score < bestScore) {
                        bestScore = score;
                        bestRoute = route;
                    }
                }
            }
            evaporate();
            updatePheromones(shipment, antRoutes);
        }

        return RoutePlanningSupport.toTravelStops(shipment, bestRoute);
    }

    @Override
    public List<TravelStop> replanRoute(Shipment shipment,
                                        TravelStop failedStop,
                                        List<Flight> availableFlights) {
        Shipment partial = Shipment.builder()
                .shipmentCode((shipment.getShipmentCode() == null ? "ACO" : shipment.getShipmentCode()) + "-A")
                .airlineName(shipment.getAirlineName())
                .originAirport(failedStop.getAirport())
                .destinationAirport(shipment.getDestinationAirport())
                .luggageCount(shipment.getLuggageCount())
                .registrationDate(failedStop.getActualArrival() != null ? failedStop.getActualArrival() : LocalDateTime.now())
                .deadline(shipment.getDeadline())
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

            double urgencyPenalty = shipment.getDeadline() == null ? 18.0
                    : Math.max(1.0, ChronoUnit.HOURS.between(LocalDateTime.now(), shipment.getDeadline()));
            operationalCost += shipment.getLuggageCount() * 0.24 + urgencyPenalty * 0.35;
            utilization += shipment.getProgressPercentage() * 0.79;
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

    void initialize(List<Flight> availableFlights) {
        pheromones.clear();
        for (Flight flight : availableFlights) {
            pheromones.put(flight.getId(), initialPheromone);
        }
    }

    List<Flight> buildSolution(Shipment shipment, List<Flight> availableFlights, Random random) {
        if (shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return List.of();
        }

        List<Flight> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Airport current = shipment.getOriginAirport();
        LocalDateTime readyAt = RoutePlanningSupport.registrationTime(shipment);
        int maxLegs = RoutePlanningSupport.maxFlightLegs(shipment);

        while (path.size() < maxLegs) {
            visited.add(current.getId());
            List<Flight> outgoing = new ArrayList<>();
            for (Flight flight : availableFlights) {
                if (!current.getId().equals(flight.getOriginAirport().getId())) {
                    continue;
                }
                if (!RoutePlanningSupport.isFeasibleNextFlight(path, readyAt, flight)) {
                    continue;
                }
                Long nextAirportId = flight.getDestinationAirport().getId();
                if (visited.contains(nextAirportId) && !nextAirportId.equals(shipment.getDestinationAirport().getId())) {
                    continue;
                }
                outgoing.add(flight);
            }

            if (outgoing.isEmpty()) {
                return List.of();
            }

            Flight chosen = rouletteSelect(outgoing, random);
            if (chosen == null) {
                return List.of();
            }

            path.add(chosen);
            if (shipment.getDestinationAirport().getId().equals(chosen.getDestinationAirport().getId())) {
                return RoutePlanningSupport.isFeasibleRoute(shipment, path) ? List.copyOf(path) : List.of();
            }

            current = chosen.getDestinationAirport();
            readyAt = chosen.getScheduledArrival();
        }

        return List.of();
    }

    void updatePheromones(Shipment shipment, List<List<Flight>> antRoutes) {
        for (List<Flight> route : antRoutes) {
            double fitness = RoutePlanningSupport.routeFitness(shipment, route);
            double delta = Math.max(0.05, fitness / 5000.0);
            for (Flight flight : route) {
                pheromones.merge(flight.getId(), delta, Double::sum);
            }
        }
    }

    void evaporate() {
        final double tauMin = 0.01;
        pheromones.replaceAll((flightId, tau) -> Math.max(tauMin, tau * (1.0 - evaporationRate)));
    }

    Flight rouletteSelect(List<Flight> candidates, Random random) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        double totalWeight = 0.0;
        List<Double> weights = new ArrayList<>(candidates.size());
        for (Flight flight : candidates) {
            double tau = pheromones.getOrDefault(flight.getId(), initialPheromone);
            double eta = 1.0 / Math.max(1.0, RoutePlanningSupport.routeScoreForSingleFlight(flight));
            double weight = Math.pow(tau, alpha) * Math.pow(eta, beta);
            weights.add(weight);
            totalWeight += weight;
        }

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights.get(i);
            if (roll <= cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        if (shipment.getRegistrationDate() == null) return true;
        LocalDate day = shipment.getRegistrationDate().toLocalDate();
        boolean afterFrom = from == null || !day.isBefore(from);
        boolean beforeTo = to == null || !day.isAfter(to);
        return afterFrom && beforeTo;
    }

    private Random randomForShipment(Shipment shipment) {
        long seed = shipment.getShipmentCode() == null ? 31L : shipment.getShipmentCode().hashCode() * 37L;
        return new Random(seed);
    }
}
