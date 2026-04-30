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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

public final class RoutePlanningSupport {

    public record PlanningFlightIndex(Map<Long, List<Flight>> flightsByOrigin,
                                      Map<Long, List<Flight>> flightsByDestination) {
    }

    private static final long MIN_CONNECTION_MINUTES = 30L;
    private static final long DEADLINE_PRUNE_GRACE_MINUTES = 360L;
    private static final int MAX_INTRA_HOPS = 2;
    private static final int MAX_INTER_HOPS = 3;
    private static final int MAX_OUTGOING_PER_NODE = 12;
    private static final int MAX_OUTGOING_SCAN_PER_NODE = 48;
    private static final int MAX_ENUMERATED_ROUTES = 96;

    private record ScoredRoute(List<Flight> route, double score) {
    }

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
        return candidateFlightsFromEligible(shipment, eligible);
    }

    static List<Flight> candidateFlightsFromEligible(Shipment shipment, List<Flight> eligible) {
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

    public static List<Flight> eligiblePlanningFlights(Shipment shipment, List<Flight> flights) {
        return eligibleFlights(shipment, flights);
    }

    public static List<Flight> planningCandidates(Shipment shipment, List<Flight> flights) {
        return candidateFlights(shipment, flights);
    }

    public static List<Flight> planningCandidatesFromEligible(Shipment shipment, List<Flight> eligibleFlights) {
        return candidateFlightsFromEligible(shipment, eligibleFlights);
    }

    public static Map<Long, List<Flight>> planningIndexByOrigin(List<Flight> flights) {
        return indexFlightsByOrigin(flights);
    }

    public static PlanningFlightIndex buildPlanningFlightIndex(List<Flight> flights) {
        return new PlanningFlightIndex(indexFlightsByOrigin(flights), indexFlightsByDestination(flights));
    }

    public static List<Flight> eligiblePlanningFlightsFromIndex(Shipment shipment, PlanningFlightIndex flightIndex) {
        if (shipment == null || flightIndex == null || shipment.getOriginAirport() == null || shipment.getOriginAirport().getId() == null) {
            return List.of();
        }
        return filterEligibleFlights(shipment, flightIndex.flightsByOrigin().get(shipment.getOriginAirport().getId()));
    }

    public static List<Flight> planningCandidatesFromIndex(Shipment shipment, PlanningFlightIndex flightIndex) {
        if (shipment == null || flightIndex == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null) {
            return List.of();
        }
        Long originId = shipment.getOriginAirport().getId();
        Long destinationId = shipment.getDestinationAirport().getId();
        if (originId == null || destinationId == null) {
            return List.of();
        }

        List<Flight> firstLegs = filterEligibleFlights(shipment, flightIndex.flightsByOrigin().get(originId)).stream()
                .limit(40)
                .toList();
        if (firstLegs.isEmpty()) {
            return List.of();
        }

        Map<Long, Flight> reduced = new LinkedHashMap<>();
        addEligibleFlights(reduced, shipment, flightIndex.flightsByOrigin().get(originId), 180);
        for (Flight firstLeg : firstLegs) {
            if (reduced.size() >= 180 || firstLeg.getDestinationAirport() == null || firstLeg.getDestinationAirport().getId() == null) {
                continue;
            }
            addEligibleFlights(reduced, shipment, flightIndex.flightsByOrigin().get(firstLeg.getDestinationAirport().getId()), 180);
        }
        addEligibleFlightsToDestination(reduced, shipment, flightIndex.flightsByDestination().get(destinationId), 180);

        return reduced.values().stream()
                .sorted(Comparator.comparing(Flight::getScheduledDeparture).thenComparing(Flight::getScheduledArrival))
                .limit(180)
                .toList();
    }

    static List<List<Flight>> enumerateRoutes(Shipment shipment, List<Flight> flights) {
        List<Flight> candidates = planningCandidates(shipment, flights);
        return enumerateRoutesFromCandidates(shipment, candidates);
    }

    static List<List<Flight>> enumerateRoutesFromCandidates(Shipment shipment, List<Flight> candidates) {
        if (shipment == null || shipment.getOriginAirport() == null || shipment.getDestinationAirport() == null || candidates.isEmpty()) {
            return List.of();
        }

        PriorityQueue<ScoredRoute> frontier = new PriorityQueue<>(Comparator.comparingDouble(ScoredRoute::score).reversed());
        Set<String> seen = new HashSet<>();
        Airport origin = shipment.getOriginAirport();
        Airport destination = shipment.getDestinationAirport();
        LocalDateTime registration = registrationTime(shipment);
        Map<Long, List<Flight>> flightsByOrigin = indexFlightsByOrigin(candidates);
        dfsEnumerate(origin, destination, registration, shipment, flightsByOrigin, maxFlightLegs(shipment), new ArrayList<>(), new HashSet<>(), frontier, seen);

        return frontier.stream()
                .sorted(Comparator.comparingDouble(ScoredRoute::score))
                .map(ScoredRoute::route)
                .toList();
    }

    static Map<Long, List<Flight>> indexFlightsByOrigin(List<Flight> flights) {
        if (flights == null || flights.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Flight>> indexed = new LinkedHashMap<>();
        for (Flight flight : flights) {
            if (flight == null || flight.getOriginAirport() == null || flight.getOriginAirport().getId() == null) {
                continue;
            }
            indexed.computeIfAbsent(flight.getOriginAirport().getId(), ignored -> new ArrayList<>()).add(flight);
        }
        indexed.values().forEach(list -> list.sort(Comparator.comparing(Flight::getScheduledDeparture).thenComparing(Flight::getScheduledArrival)));
        return indexed;
    }

    static Map<Long, List<Flight>> indexFlightsByDestination(List<Flight> flights) {
        if (flights == null || flights.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Flight>> indexed = new LinkedHashMap<>();
        for (Flight flight : flights) {
            if (flight == null || flight.getDestinationAirport() == null || flight.getDestinationAirport().getId() == null) {
                continue;
            }
            indexed.computeIfAbsent(flight.getDestinationAirport().getId(), ignored -> new ArrayList<>()).add(flight);
        }
        indexed.values().forEach(list -> list.sort(Comparator.comparing(Flight::getScheduledDeparture).thenComparing(Flight::getScheduledArrival)));
        return indexed;
    }

    private static List<Flight> filterEligibleFlights(Shipment shipment, List<Flight> flights) {
        if (shipment == null || flights == null || flights.isEmpty()) {
            return List.of();
        }
        int requiredCapacity = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        LocalDateTime registration = registrationTime(shipment);
        List<Flight> eligible = new ArrayList<>();
        for (Flight flight : flights) {
            if (flight == null || flight.getStatus() != FlightStatus.SCHEDULED) {
                continue;
            }
            if (flight.getOriginAirport() == null || flight.getDestinationAirport() == null) {
                continue;
            }
            if (flight.getScheduledDeparture() == null || flight.getScheduledArrival() == null) {
                continue;
            }
            if (flight.getScheduledArrival().isBefore(flight.getScheduledDeparture())) {
                continue;
            }
            if (flight.getAvailableCapacity() < requiredCapacity) {
                continue;
            }
            if (flight.getScheduledDeparture().isBefore(registration)) {
                continue;
            }
            eligible.add(flight);
        }
        return eligible;
    }

    private static void addEligibleFlights(Map<Long, Flight> reduced, Shipment shipment, List<Flight> flights, int limit) {
        if (reduced.size() >= limit) {
            return;
        }
        for (Flight flight : filterEligibleFlights(shipment, flights)) {
            if (flight.getId() != null) {
                reduced.putIfAbsent(flight.getId(), flight);
            }
            if (reduced.size() >= limit) {
                return;
            }
        }
    }

    private static void addEligibleFlightsToDestination(Map<Long, Flight> reduced, Shipment shipment, List<Flight> flights, int limit) {
        if (reduced.size() >= limit || shipment == null || shipment.getDestinationAirport() == null || shipment.getDestinationAirport().getId() == null) {
            return;
        }
        Long destinationId = shipment.getDestinationAirport().getId();
        for (Flight flight : filterEligibleFlights(shipment, flights)) {
            if (flight.getDestinationAirport() == null || !destinationId.equals(flight.getDestinationAirport().getId())) {
                continue;
            }
            if (flight.getId() != null) {
                reduced.putIfAbsent(flight.getId(), flight);
            }
            if (reduced.size() >= limit) {
                return;
            }
        }
    }

    private static List<Flight> outgoingFlights(Shipment shipment,
                                                Airport current,
                                                Airport destination,
                                                LocalDateTime readyAt,
                                                int pathSize,
                                                int maxLegs,
                                                Set<Long> visitedAirportIds,
                                                List<Flight> flightsFromCurrent) {
        if (flightsFromCurrent == null || flightsFromCurrent.isEmpty()) {
            return List.of();
        }

        LocalDateTime departureCutoff = departureCutoff(shipment, pathSize, maxLegs);
        int startIndex = lowerBoundByDeparture(flightsFromCurrent, readyAt);
        int inspected = 0;
        List<Flight> outgoing = new ArrayList<>();
        Long destinationId = destination == null ? null : destination.getId();

        for (int i = startIndex; i < flightsFromCurrent.size() && inspected < MAX_OUTGOING_SCAN_PER_NODE; i++) {
            Flight flight = flightsFromCurrent.get(i);
            inspected++;
            if (!isFeasibleNextFlight(readyAt, flight)) {
                continue;
            }
            if (departureCutoff != null && flight.getScheduledDeparture().isAfter(departureCutoff)) {
                break;
            }

            Airport nextAirport = flight.getDestinationAirport();
            Long nextAirportId = nextAirport == null ? null : nextAirport.getId();
            if (nextAirportId == null) {
                continue;
            }
            if (!nextAirportId.equals(destinationId) && visitedAirportIds.contains(nextAirportId)) {
                continue;
            }
            if (shouldPruneByDeadline(shipment, flight, pathSize, maxLegs)) {
                continue;
            }

            outgoing.add(flight);
        }

        return outgoing.stream()
                .sorted(Comparator.comparingDouble(flight -> localHeuristicScore(shipment, current, destination, flight)))
                .limit(MAX_OUTGOING_PER_NODE)
                .toList();
    }

    private static int lowerBoundByDeparture(List<Flight> flights, LocalDateTime readyAt) {
        if (readyAt == null || flights.isEmpty()) {
            return 0;
        }
        int low = 0;
        int high = flights.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            Flight flight = flights.get(mid);
            LocalDateTime departure = flight.getScheduledDeparture();
            if (departure == null || departure.isBefore(readyAt)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static LocalDateTime nextReadyAt(Flight flight) {
        if (flight == null || flight.getScheduledArrival() == null) {
            return null;
        }
        return flight.getScheduledArrival().plusMinutes(MIN_CONNECTION_MINUTES);
    }

    private static void addEnumeratedRoute(Shipment shipment,
                                           List<Flight> path,
                                           PriorityQueue<ScoredRoute> frontier) {
        List<Flight> route = List.copyOf(path);
        double score = routeScoreForFeasibleRoute(shipment, route);
        if (!Double.isFinite(score)) {
            return;
        }

        frontier.offer(new ScoredRoute(route, score));
        if (frontier.size() > MAX_ENUMERATED_ROUTES) {
            frontier.poll();
        }
    }

    private static boolean shouldPruneByDeadline(Shipment shipment, Flight flight, int pathSize, int maxLegs) {
        if (shipment == null || shipment.getDeadline() == null || flight == null || flight.getScheduledArrival() == null) {
            return false;
        }
        int remainingLegs = Math.max(0, maxLegs - (pathSize + 1));
        long graceMinutes = DEADLINE_PRUNE_GRACE_MINUTES + remainingLegs * 180L;
        return flight.getScheduledArrival().isAfter(shipment.getDeadline().plusMinutes(graceMinutes));
    }

    private static LocalDateTime departureCutoff(Shipment shipment, int pathSize, int maxLegs) {
        if (shipment == null || shipment.getDeadline() == null) {
            return null;
        }
        int remainingLegs = Math.max(0, maxLegs - pathSize);
        long graceMinutes = DEADLINE_PRUNE_GRACE_MINUTES + remainingLegs * 180L;
        return shipment.getDeadline().plusMinutes(graceMinutes);
    }

    private static double localHeuristicScore(Shipment shipment,
                                              Airport current,
                                              Airport destination,
                                              Flight flight) {
        if (flight == null) {
            return Double.POSITIVE_INFINITY;
        }

        double etaMinutes = Math.max(0, ChronoUnit.MINUTES.between(registrationTime(shipment), flight.getScheduledArrival()));
        double remainingDistance = airportDistance(flight.getDestinationAirport(), destination);
        double currentDistance = airportDistance(current, destination);
        double progressPenalty = remainingDistance > currentDistance ? (remainingDistance - currentDistance) * 0.6 : 0.0;
        double loadPenalty = Math.max(0.0, flight.getLoadPct() - 75.0) * 2.2 + Math.max(0.0, flight.getReservedLoadPct() - 75.0) * 1.4;
        double nodePenalty = flight.getDestinationAirport() == null ? 0.0
                : Math.max(0.0, flight.getDestinationAirport().getOccupancyPct() - 70.0) * 1.6;
        double deadlinePenalty = 0.0;
        if (shipment != null && shipment.getDeadline() != null && flight.getScheduledArrival() != null
                && flight.getScheduledArrival().isAfter(shipment.getDeadline())) {
            long minutesLate = Math.max(1, ChronoUnit.MINUTES.between(shipment.getDeadline(), flight.getScheduledArrival()));
            deadlinePenalty = 3000.0 + minutesLate * 4.0;
        }
        return etaMinutes + remainingDistance * 0.35 + progressPenalty + loadPenalty + nodePenalty + deadlinePenalty;
    }

    private static double airportDistance(Airport origin, Airport destination) {
        if (origin == null || destination == null
                || origin.getLatitude() == null || origin.getLongitude() == null
                || destination.getLatitude() == null || destination.getLongitude() == null) {
            return 0.0;
        }
        double dLat = origin.getLatitude() - destination.getLatitude();
        double dLon = origin.getLongitude() - destination.getLongitude();
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    private static void dfsEnumerate(Airport current,
                                     Airport destination,
                                     LocalDateTime readyAt,
                                     Shipment shipment,
                                     Map<Long, List<Flight>> flightsByOrigin,
                                     int maxLegs,
                                     List<Flight> path,
                                     Set<Long> visitedAirportIds,
                                     PriorityQueue<ScoredRoute> frontier,
                                     Set<String> seen) {
        if (current == null || destination == null || path.size() >= maxLegs) {
            return;
        }

        Long currentAirportId = current.getId();
        if (currentAirportId == null) {
            return;
        }

        visitedAirportIds.add(currentAirportId);
        List<Flight> outgoing = outgoingFlights(
                shipment,
                current,
                destination,
                readyAt,
                path.size(),
                maxLegs,
                visitedAirportIds,
                flightsByOrigin.get(currentAirportId)
        );

        for (Flight flight : outgoing) {
            path.add(flight);
            if (destination.getId().equals(flight.getDestinationAirport().getId())) {
                String key = path.stream().map(Flight::getId).map(String::valueOf).collect(Collectors.joining("-"));
                if (seen.add(key)) {
                    addEnumeratedRoute(shipment, path, frontier);
                }
            } else {
                dfsEnumerate(
                        flight.getDestinationAirport(),
                        destination,
                        nextReadyAt(flight),
                        shipment,
                        flightsByOrigin,
                        maxLegs,
                        path,
                        visitedAirportIds,
                        frontier,
                        seen
                );
            }
            path.remove(path.size() - 1);
        }

        visitedAirportIds.remove(currentAirportId);
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
        LocalDateTime earliestDeparture = readyAt;
        if (!currentPath.isEmpty()) {
            Flight previous = currentPath.get(currentPath.size() - 1);
            earliestDeparture = previous.getScheduledArrival() == null
                    ? readyAt
                    : previous.getScheduledArrival().plusMinutes(MIN_CONNECTION_MINUTES);
        }
        return isFeasibleNextFlight(earliestDeparture, candidate);
    }

    static boolean isFeasibleNextFlight(LocalDateTime earliestDeparture, Flight candidate) {
        if (candidate == null || candidate.getScheduledDeparture() == null || candidate.getScheduledArrival() == null) {
            return false;
        }
        if (candidate.getStatus() != FlightStatus.SCHEDULED || candidate.getAvailableCapacity() <= 0) {
            return false;
        }
        if (candidate.getScheduledArrival().isBefore(candidate.getScheduledDeparture())) {
            return false;
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

        return routeScoreForFeasibleRoute(shipment, route);
    }

    static double routeScoreForFeasibleRoute(Shipment shipment, List<Flight> route) {
        if (route == null || route.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        LocalDateTime registration = registrationTime(shipment);
        LocalDateTime finalArrival = route.get(route.size() - 1).getScheduledArrival();
        long etaMinutes = Math.max(0, ChronoUnit.MINUTES.between(registration, finalArrival));

        double loadPenalty = 0.0;
        Set<Long> countedAirportIds = new HashSet<>();
        double nodePenalty = 0.0;
        for (Flight flight : route) {
            loadPenalty += Math.max(0.0, flight.getLoadPct() - 75.0) * 2.4;
            Airport airport = flight.getDestinationAirport();
            if (airport != null && airport.getId() != null && countedAirportIds.add(airport.getId())) {
                nodePenalty += Math.max(0.0, airport.getOccupancyPct() - 70.0) * 1.7;
            }
        }
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
        return routeFitnessForScore(score);
    }

    static double routeFitnessForScore(double score) {
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
