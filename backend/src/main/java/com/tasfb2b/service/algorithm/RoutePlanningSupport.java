package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class RoutePlanningSupport {

    private static final long MIN_CONNECTION_MINUTES = 30L;
    private static final int MAX_INTRA_HOPS = 2;
    private static final int MAX_INTER_HOPS = 3;

    private RoutePlanningSupport() {
    }

    static int maxFlightLegs(Shipment shipment) {
        if (shipment == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return MAX_INTRA_HOPS;
        }
        boolean interContinental = shipment.getOriginAirport().getContinent() != shipment.getDestinationAirport().getContinent();
        return interContinental ? MAX_INTER_HOPS : MAX_INTRA_HOPS;
    }

    static List<Flight> eligibleFlights(Shipment shipment, List<Flight> flights) {
        if (shipment == null || flights == null || flights.isEmpty()) {
            return List.of();
        }
        int requiredCapacity = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        LocalDateTime registration = registrationTime(shipment);

        return flights.stream()
                .filter(Objects::nonNull)
                .filter(flight -> flight.getStatus() == FlightStatus.SCHEDULED)
                .filter(flight -> flight.getOriginAirport() != null && flight.getDestinationAirport() != null)
                .filter(flight -> flight.getScheduledDeparture() != null && flight.getScheduledArrival() != null)
                .filter(flight -> !flight.getScheduledArrival().isBefore(flight.getScheduledDeparture()))
                .filter(flight -> flight.getAvailableCapacity() >= requiredCapacity)
                .filter(flight -> !flight.getScheduledDeparture().isBefore(registration))
                .sorted(Comparator.comparing(Flight::getScheduledDeparture).thenComparing(Flight::getScheduledArrival))
                .toList();
    }

    static List<Flight> candidateFlights(Shipment shipment, List<Flight> flights) {
        List<Flight> eligible = eligibleFlights(shipment, flights);
        if (eligible.isEmpty() || shipment == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return eligible;
        }

        Long originId = shipment.getOriginAirport().getId();
        Long destinationId = shipment.getDestinationAirport().getId();
        if (originId == null || destinationId == null) {
            return eligible;
        }

        List<Flight> firstLegs = eligible.stream()
                .filter(flight -> originId.equals(flight.getOriginAirport().getId()))
                .limit(40)
                .toList();
        if (firstLegs.isEmpty()) {
            return eligible.stream().limit(160).toList();
        }

        Set<Long> pivotAirports = new HashSet<>();
        pivotAirports.add(originId);
        firstLegs.forEach(flight -> pivotAirports.add(flight.getDestinationAirport().getId()));

        List<Flight> reduced = eligible.stream()
                .filter(flight -> pivotAirports.contains(flight.getOriginAirport().getId())
                        || destinationId.equals(flight.getDestinationAirport().getId()))
                .limit(180)
                .toList();

        return reduced.isEmpty() ? eligible.stream().limit(180).toList() : reduced;
    }

    static List<List<Flight>> enumerateRoutes(Shipment shipment, List<Flight> flights) {
        List<Flight> candidates = candidateFlights(shipment, flights);
        if (shipment == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null || candidates.isEmpty()) {
            return List.of();
        }

        List<List<Flight>> routes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Airport origin = shipment.getOriginAirport();
        Airport destination = shipment.getDestinationAirport();
        LocalDateTime registration = registrationTime(shipment);
        dfsEnumerate(origin, destination, registration, candidates, maxFlightLegs(shipment), new ArrayList<>(), new HashSet<>(), routes, seen);

        routes.sort(Comparator.comparingDouble(route -> routeScore(shipment, route)));
        return routes;
    }

    private static void dfsEnumerate(Airport current,
                                     Airport destination,
                                     LocalDateTime readyAt,
                                     List<Flight> flights,
                                     int maxLegs,
                                     List<Flight> path,
                                     Set<Long> visitedAirportIds,
                                     List<List<Flight>> routes,
                                     Set<String> seen) {
        if (current == null || destination == null || path.size() >= maxLegs) {
            return;
        }

        visitedAirportIds.add(current.getId());
        List<Flight> outgoing = flights.stream()
                .filter(flight -> current.getId().equals(flight.getOriginAirport().getId()))
                .filter(flight -> isFeasibleNextFlight(path, readyAt, flight))
                .filter(flight -> !visitedAirportIds.contains(flight.getDestinationAirport().getId()) || flight.getDestinationAirport().getId().equals(destination.getId()))
                .limit(24)
                .toList();

        for (Flight flight : outgoing) {
            path.add(flight);
            if (destination.getId().equals(flight.getDestinationAirport().getId())) {
                String key = path.stream().map(Flight::getId).map(String::valueOf).collect(Collectors.joining("-"));
                if (seen.add(key)) {
                    routes.add(List.copyOf(path));
                }
            } else {
                dfsEnumerate(
                        flight.getDestinationAirport(),
                        destination,
                        readyAt,
                        flights,
                        maxLegs,
                        path,
                        visitedAirportIds,
                        routes,
                        seen
                );
            }
            path.remove(path.size() - 1);
        }

        visitedAirportIds.remove(current.getId());
    }

    static boolean isFeasibleRoute(Shipment shipment, List<Flight> route) {
        if (shipment == null || route == null || route.isEmpty()) {
            return false;
        }
        if (route.size() > maxFlightLegs(shipment)) {
            return false;
        }
        if (shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return false;
        }

        Airport current = shipment.getOriginAirport();
        LocalDateTime readyAt = registrationTime(shipment);
        Set<Long> visited = new HashSet<>();
        visited.add(current.getId());

        for (Flight flight : route) {
            if (flight == null || flight.getOriginAirport() == null || flight.getDestinationAirport() == null) {
                return false;
            }
            if (!current.getId().equals(flight.getOriginAirport().getId())) {
                return false;
            }
            if (!isFeasibleNextFlight(Collections.emptyList(), readyAt, flight)) {
                return false;
            }
            current = flight.getDestinationAirport();
            readyAt = flight.getScheduledArrival();
            if (!current.getId().equals(shipment.getDestinationAirport().getId()) && !visited.add(current.getId())) {
                return false;
            }
        }

        return current.getId().equals(shipment.getDestinationAirport().getId());
    }

    static boolean isFeasibleNextFlight(List<Flight> currentPath, LocalDateTime readyAt, Flight candidate) {
        if (candidate == null || candidate.getScheduledDeparture() == null || candidate.getScheduledArrival() == null) {
            return false;
        }
        if (candidate.getStatus() != FlightStatus.SCHEDULED || candidate.getAvailableCapacity() <= 0) {
            return false;
        }
        if (candidate.getScheduledArrival().isBefore(candidate.getScheduledDeparture())) {
            return false;
        }
        LocalDateTime earliestDeparture = readyAt;
        if (!currentPath.isEmpty()) {
            Flight previous = currentPath.get(currentPath.size() - 1);
            earliestDeparture = previous.getScheduledArrival() == null
                    ? readyAt
                    : previous.getScheduledArrival().plusMinutes(MIN_CONNECTION_MINUTES);
        }
        return earliestDeparture == null || !candidate.getScheduledDeparture().isBefore(earliestDeparture);
    }

    static List<TravelStop> toTravelStops(Shipment shipment, List<Flight> route) {
        if (!isFeasibleRoute(shipment, route)) {
            return List.of();
        }

        List<TravelStop> stops = new ArrayList<>();
        stops.add(TravelStop.builder()
                .shipment(shipment)
                .airport(shipment.getOriginAirport())
                .flight(null)
                .stopOrder(0)
                .scheduledArrival(registrationTime(shipment))
                .stopStatus(StopStatus.PENDING)
                .build());

        int order = 1;
        for (Flight flight : route) {
            stops.add(TravelStop.builder()
                    .shipment(shipment)
                    .airport(flight.getDestinationAirport())
                    .flight(flight)
                    .stopOrder(order++)
                    .scheduledArrival(flight.getScheduledArrival())
                    .stopStatus(StopStatus.PENDING)
                    .build());
        }
        return stops;
    }

    static double routeScore(Shipment shipment, List<Flight> route) {
        if (!isFeasibleRoute(shipment, route)) {
            return Double.POSITIVE_INFINITY;
        }

        LocalDateTime registration = registrationTime(shipment);
        LocalDateTime finalArrival = route.get(route.size() - 1).getScheduledArrival();
        long etaMinutes = Math.max(0, ChronoUnit.MINUTES.between(registration, finalArrival));

        double loadPenalty = route.stream()
                .mapToDouble(flight -> Math.max(0.0, flight.getLoadPct() - 75.0) * 2.4)
                .sum();
        double nodePenalty = route.stream()
                .map(Flight::getDestinationAirport)
                .filter(Objects::nonNull)
                .distinct()
                .mapToDouble(airport -> Math.max(0.0, airport.getOccupancyPct() - 70.0) * 1.7)
                .sum();
        double hopPenalty = Math.max(0, route.size() - 1) * 45.0;

        double deadlinePenalty = 0.0;
        if (shipment.getDeadline() != null && finalArrival.isAfter(shipment.getDeadline())) {
            long minutesLate = Math.max(1, ChronoUnit.MINUTES.between(shipment.getDeadline(), finalArrival));
            deadlinePenalty = 5000.0 + minutesLate * 4.0;
        }

        return etaMinutes + loadPenalty + nodePenalty + hopPenalty + deadlinePenalty;
    }

    static double routeFitness(Shipment shipment, List<Flight> route) {
        double score = routeScore(shipment, route);
        if (!Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(1.0, 10000.0 - score);
    }

    static double routeScoreForSingleFlight(Flight flight) {
        if (flight == null) {
            return Double.POSITIVE_INFINITY;
        }
        double loadPenalty = Math.max(0.0, flight.getLoadPct() - 75.0) * 2.4;
        double nodePenalty = flight.getDestinationAirport() == null ? 0.0
                : Math.max(0.0, flight.getDestinationAirport().getOccupancyPct() - 70.0) * 1.7;
        return 60.0 + loadPenalty + nodePenalty;
    }

    static LocalDateTime estimatedArrival(Shipment shipment, List<Flight> route) {
        if (route == null || route.isEmpty()) {
            return registrationTime(shipment);
        }
        return route.get(route.size() - 1).getScheduledArrival();
    }

    static LocalDateTime registrationTime(Shipment shipment) {
        return shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
    }

    static List<Flight> normalizeRoute(List<Flight> route) {
        if (route == null || route.isEmpty()) {
            return List.of();
        }
        return List.copyOf(route);
    }
}
