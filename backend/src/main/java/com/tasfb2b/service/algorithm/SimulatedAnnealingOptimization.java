package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Service("simulatedAnnealingOptimization")
public class SimulatedAnnealingOptimization implements RouteOptimizer {

    private int iterations = 220;
    private double initialTemperature = 160.0;
    private double coolingRate = 0.965;

    public void setIterations(int iterations) {
        this.iterations = Math.max(60, iterations);
    }

    public void setInitialTemperature(double initialTemperature) {
        this.initialTemperature = Math.max(20.0, initialTemperature);
    }

    public void setCoolingRate(double coolingRate) {
        this.coolingRate = Math.max(0.80, Math.min(0.995, coolingRate));
    }

    @Override
    public String getAlgorithmName() {
        return "Simulated Annealing";
    }

    @Override
    public List<TravelStop> planRoute(Shipment shipment,
                                      List<Flight> availableFlights,
                                      List<Airport> airports) {
        if (availableFlights == null || availableFlights.isEmpty()) {
            return List.of();
        }

        List<List<Flight>> candidates = buildCandidates(shipment, availableFlights);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Random random = new Random(Math.abs((shipment.getShipmentCode() == null ? "SA" : shipment.getShipmentCode()).hashCode()));
        List<Flight> current = candidates.get(0);
        double currentEnergy = energy(shipment, current);
        List<Flight> best = current;
        double bestEnergy = currentEnergy;

        double temperature = initialTemperature;
        for (int i = 0; i < iterations; i++) {
            List<Flight> neighbor = candidates.get(random.nextInt(candidates.size()));
            double neighborEnergy = energy(shipment, neighbor);
            if (accept(currentEnergy, neighborEnergy, temperature, random)) {
                current = neighbor;
                currentEnergy = neighborEnergy;
                if (neighborEnergy < bestEnergy) {
                    best = neighbor;
                    bestEnergy = neighborEnergy;
                }
            }
            temperature *= coolingRate;
            if (temperature < 1e-3) {
                break;
            }
        }

        return toStops(shipment, best);
    }

    @Override
    public List<TravelStop> replanRoute(Shipment shipment,
                                        TravelStop failedStop,
                                        List<Flight> availableFlights) {
        Shipment partial = Shipment.builder()
                .shipmentCode((shipment.getShipmentCode() == null ? "SA" : shipment.getShipmentCode()) + "-SA-R")
                .airlineName(shipment.getAirlineName())
                .originAirport(failedStop.getAirport())
                .destinationAirport(shipment.getDestinationAirport())
                .luggageCount(shipment.getLuggageCount())
                .registrationDate(LocalDateTime.now())
                .deadline(shipment.getDeadline())
                .build();

        List<Airport> airports = availableFlights.stream()
                .flatMap(flight -> List.of(flight.getOriginAirport(), flight.getDestinationAirport()).stream())
                .distinct()
                .toList();

        List<TravelStop> replanned = planRoute(partial, availableFlights, airports);
        int baseOrder = failedStop.getStopOrder();
        for (TravelStop stop : replanned) {
            stop.setStopOrder(baseOrder + stop.getStopOrder());
        }
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
        int delayed = 0;
        int saturated = 0;
        double transitHours = 0.0;
        double utilization = 0.0;
        double cost = 0.0;
        LocalDateTime collapseAt = null;

        for (Shipment shipment : filtered) {
            if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
                completed++;
                if (shipment.getDeliveredAt() != null && shipment.getRegistrationDate() != null) {
                    transitHours += Math.max(0, ChronoUnit.HOURS.between(shipment.getRegistrationDate(), shipment.getDeliveredAt()));
                }
            }
            if (shipment.getStatus() == ShipmentStatus.DELAYED) delayed++;
            if (shipment.getStatus() == ShipmentStatus.CRITICAL || shipment.getStatus() == ShipmentStatus.DELAYED) {
                saturated++;
                if (collapseAt == null) {
                    collapseAt = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
                }
            }
            utilization += shipment.getProgressPercentage() == null ? 0.0 : shipment.getProgressPercentage() * 0.78;
            cost += (shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount()) * 0.24;
        }

        return OptimizationResult.builder()
                .algorithmName(getAlgorithmName())
                .completedShipments(completed)
                .completedPct(total == 0 ? 0.0 : completed * 100.0 / total)
                .avgTransitHours(completed == 0 ? 0.0 : transitHours / completed)
                .totalReplanning(delayed)
                .operationalCost(total == 0 ? 0.0 : cost)
                .flightUtilizationPct(total == 0 ? 0.0 : utilization / total)
                .saturatedAirports(saturated)
                .collapseReachedAt(collapseAt)
                .build();
    }

    private List<List<Flight>> buildCandidates(Shipment shipment, List<Flight> flights) {
        List<List<Flight>> candidates = new ArrayList<>();

        List<Flight> direct = flights.stream()
                .filter(flight -> flight.getOriginAirport().getId().equals(shipment.getOriginAirport().getId()))
                .filter(flight -> flight.getDestinationAirport().getId().equals(shipment.getDestinationAirport().getId()))
                .sorted(Comparator.comparing(Flight::getScheduledArrival, Comparator.nullsLast(LocalDateTime::compareTo)))
                .limit(6)
                .toList();
        for (Flight flight : direct) {
            candidates.add(List.of(flight));
        }

        List<Flight> firstLegs = flights.stream()
                .filter(flight -> flight.getOriginAirport().getId().equals(shipment.getOriginAirport().getId()))
                .sorted(Comparator.comparing(Flight::getScheduledDeparture, Comparator.nullsLast(LocalDateTime::compareTo)))
                .limit(24)
                .toList();

        for (Flight first : firstLegs) {
            List<Flight> secondLegs = flights.stream()
                    .filter(flight -> flight.getOriginAirport().getId().equals(first.getDestinationAirport().getId()))
                    .filter(flight -> flight.getDestinationAirport().getId().equals(shipment.getDestinationAirport().getId()))
                    .filter(flight -> flight.getScheduledDeparture() != null && first.getScheduledArrival() != null)
                    .filter(flight -> !flight.getScheduledDeparture().isBefore(first.getScheduledArrival()))
                    .sorted(Comparator.comparing(Flight::getScheduledArrival, Comparator.nullsLast(LocalDateTime::compareTo)))
                    .limit(3)
                    .toList();
            for (Flight second : secondLegs) {
                candidates.add(List.of(first, second));
            }
        }

        return candidates;
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        LocalDate date = shipment.getRegistrationDate() == null ? null : shipment.getRegistrationDate().toLocalDate();
        if (date == null) return true;
        boolean fromOk = from == null || !date.isBefore(from);
        boolean toOk = to == null || !date.isAfter(to);
        return fromOk && toOk;
    }

    private boolean accept(double current, double neighbor, double temp, Random random) {
        if (neighbor <= current) return true;
        double delta = neighbor - current;
        double probability = Math.exp(-delta / Math.max(temp, 1e-6));
        return random.nextDouble() < probability;
    }

    private double energy(Shipment shipment, List<Flight> route) {
        if (route == null || route.isEmpty()) {
            return Double.MAX_VALUE;
        }

        LocalDateTime arrival = route.get(route.size() - 1).getScheduledArrival();
        LocalDateTime departure = route.get(0).getScheduledDeparture();
        if (arrival == null || departure == null) {
            return Double.MAX_VALUE;
        }

        double hours = Math.max(0.0, ChronoUnit.MINUTES.between(departure, arrival) / 60.0);
        double loadPenalty = route.stream()
                .mapToDouble(flight -> flight.getMaxCapacity() <= 0 ? 1.0 : ((double) flight.getCurrentLoad() / flight.getMaxCapacity()))
                .sum() * 6.5;
        double hopPenalty = Math.max(0, route.size() - 1) * 8.0;

        double deadlinePenalty = 0.0;
        if (shipment.getDeadline() != null && arrival.isAfter(shipment.getDeadline())) {
            deadlinePenalty = 120.0 + ChronoUnit.HOURS.between(shipment.getDeadline(), arrival) * 12.0;
        }

        return hours + loadPenalty + hopPenalty + deadlinePenalty;
    }

    private List<TravelStop> toStops(Shipment shipment, List<Flight> route) {
        if (route == null || route.isEmpty()) {
            return List.of();
        }

        List<TravelStop> stops = new ArrayList<>();
        stops.add(TravelStop.builder()
                .airport(shipment.getOriginAirport())
                .flight(null)
                .stopOrder(0)
                .stopStatus(StopStatus.PENDING)
                .scheduledArrival(shipment.getRegistrationDate())
                .build());

        int order = 1;
        for (Flight flight : route) {
            stops.add(TravelStop.builder()
                    .airport(flight.getDestinationAirport())
                    .flight(flight)
                    .stopOrder(order++)
                    .stopStatus(StopStatus.PENDING)
                    .scheduledArrival(flight.getScheduledArrival())
                    .build());
        }
        return stops;
    }
}
