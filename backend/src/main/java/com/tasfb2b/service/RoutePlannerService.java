package com.tasfb2b.service;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.AirportStatus;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import com.tasfb2b.service.algorithm.OptimizationResult;
import com.tasfb2b.service.algorithm.RouteOptimizer;
import com.tasfb2b.service.algorithm.SimulatedAnnealingOptimization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class RoutePlannerService {

    private final GeneticAlgorithm geneticAlgorithm;
    private final AntColonyOptimization antColonyOptimization;
    private final SimulatedAnnealingOptimization simulatedAnnealingOptimization;

    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final SimulationConfigRepository configRepository;
    private final ShipmentAuditService shipmentAuditService;
    private final FlightScheduleService flightScheduleService;
    private final OperationalAlertService operationalAlertService;

    public RoutePlannerService(
            GeneticAlgorithm geneticAlgorithm,
            AntColonyOptimization antColonyOptimization,
            SimulatedAnnealingOptimization simulatedAnnealingOptimization,
            AirportRepository airportRepository,
            FlightRepository flightRepository,
            ShipmentRepository shipmentRepository,
            TravelStopRepository travelStopRepository,
            SimulationConfigRepository configRepository,
            ShipmentAuditService shipmentAuditService,
            FlightScheduleService flightScheduleService,
            OperationalAlertService operationalAlertService
    ) {
        this.geneticAlgorithm = geneticAlgorithm;
        this.antColonyOptimization = antColonyOptimization;
        this.simulatedAnnealingOptimization = simulatedAnnealingOptimization;
        this.airportRepository = airportRepository;
        this.flightRepository = flightRepository;
        this.shipmentRepository = shipmentRepository;
        this.travelStopRepository = travelStopRepository;
        this.configRepository = configRepository;
        this.shipmentAuditService = shipmentAuditService;
        this.flightScheduleService = flightScheduleService;
        this.operationalAlertService = operationalAlertService;
    }

    public List<TravelStop> planShipment(Shipment shipment, String algorithmName) {
        return planShipment(shipment, algorithmName,
                flightScheduleService.availableFlightsForShipment(shipment.getRegistrationDate()),
                airportRepository.findAll(),
                true);
    }

    public List<TravelStop> planShipment(Shipment shipment,
                                         String algorithmName,
                                         List<Flight> availableFlights,
                                         List<Airport> airports,
                                         boolean persistFlightLoadImmediately) {
        RouteOptimizer optimizer = resolveOptimizer(algorithmName);
        List<Flight> eligibleFlights = candidateFlightsForShipment(shipment, eligibleFlightsForShipment(shipment, availableFlights));

        List<TravelStop> directStops = buildDirectCandidate(shipment, eligibleFlights);
        List<TravelStop> multiHopStops = optimizer.planRoute(shipment, eligibleFlights, airports);

        CandidatePlan selected = selectBestCandidate(shipment, directStops, multiHopStops);
        List<TravelStop> stops = selected.stops();

        if (stops.isEmpty()) {
            shipment.setStatus(ShipmentStatus.CRITICAL);
            shipment.setProgressPercentage(0.0);
            shipmentRepository.save(shipment);
            operationalAlertService.ensureShipmentAlert(shipment, "NO_ROUTE", "No se encontró ruta factible para el envío");
            return stops;
        }

        stops.forEach(stop -> stop.setShipment(shipment));
        travelStopRepository.saveAll(stops);

        allocateFlightLoads(shipment, stops, persistFlightLoadImmediately);

        shipment.setStatus(ShipmentStatus.PENDING);
        shipment.setProgressPercentage(0.0);
        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.ROUTE_PLANNED,
                buildPlanningMessage(algorithmName, selected),
                shipment.getOriginAirport(),
                null
        );

        return stops;
    }

    @Transactional(readOnly = true)
    public List<TravelStop> previewRoute(Shipment shipment, String algorithmName) {
        return previewRoute(
                shipment,
                algorithmName,
                flightScheduleService.availableFlightsForShipment(shipment.getRegistrationDate()),
                airportRepository.findAll()
        );
    }

    @Transactional(readOnly = true)
    public List<TravelStop> previewRoute(Shipment shipment,
                                         String algorithmName,
                                         List<Flight> availableFlights,
                                         List<Airport> airports) {
        RouteOptimizer optimizer = resolveOptimizer(algorithmName);
        List<Flight> eligibleFlights = candidateFlightsForShipment(shipment, eligibleFlightsForShipment(shipment, availableFlights));
        List<TravelStop> directStops = buildDirectCandidate(shipment, eligibleFlights);
        List<TravelStop> multiHopStops = optimizer.planRoute(shipment, eligibleFlights, airports);
        return selectBestCandidate(shipment, directStops, multiHopStops).stops();
    }

    public List<TravelStop> replanShipment(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));

        List<TravelStop> currentStops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        TravelStop failedStop = currentStops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.PENDING || stop.getStopStatus() == StopStatus.IN_TRANSIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay paradas pendientes para replanificar"));

        RouteOptimizer optimizer = resolveOptimizer(getActiveAlgorithmName());
        List<TravelStop> newStops = optimizer.replanRoute(
                shipment,
                failedStop,
                flightScheduleService.availableFlightsForShipment(shipment.getRegistrationDate())
        );

        if (newStops.isEmpty()) {
            shipment.setStatus(ShipmentStatus.DELAYED);
            shipmentRepository.save(shipment);
            operationalAlertService.ensureShipmentAlert(shipment, "REPLAN_FAILED", "La replanificación no encontró una nueva ruta operativa");
            return newStops;
        }

        releaseFlightLoads(
                shipment,
                currentStops.stream()
                        .filter(stop -> stop.getStopStatus() == StopStatus.PENDING || stop.getStopStatus() == StopStatus.IN_TRANSIT)
                        .toList(),
                true
        );

        travelStopRepository.deleteAll(
                currentStops.stream()
                        .filter(stop -> stop.getStopStatus() == StopStatus.PENDING || stop.getStopStatus() == StopStatus.IN_TRANSIT)
                        .toList()
        );

        newStops.forEach(stop -> stop.setShipment(shipment));
        travelStopRepository.saveAll(newStops);

        allocateFlightLoads(shipment, newStops, true);

        shipment.setProgressPercentage(calculateProgress(shipment));
        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.ROUTE_REPLANNED,
                "Ruta replanificada con " + getActiveAlgorithmName() + " (" + (newStops.size() - 1) + " tramos)",
                failedStop.getAirport(),
                null
        );

        return newStops;
    }

    @Transactional(readOnly = true)
    public List<Flight> availableFlightsForWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        LocalDateTime from = fromInclusive == null ? LocalDateTime.now() : fromInclusive;
        LocalDateTime to = toExclusive == null || !toExclusive.isAfter(from) ? from.plusDays(3) : toExclusive;
        flightScheduleService.ensureFlightsForWindow(from, to);
        return flightRepository.findSchedulableFlightsBetween(from, to);
    }

    @Transactional(readOnly = true)
    public List<Airport> allAirports() {
        return airportRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, OptimizationResult> runBothAlgorithms(List<Shipment> shipments) {
        return runBothAlgorithms(shipments, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, OptimizationResult> runBothAlgorithms(List<Shipment> shipments, LocalDate from, LocalDate to) {
        Map<String, OptimizationResult> results = new HashMap<>();
        results.put(
                geneticAlgorithm.getAlgorithmName(),
                geneticAlgorithm.evaluatePerformance(shipments, from, to)
        );
        results.put(
                antColonyOptimization.getAlgorithmName(),
                antColonyOptimization.evaluatePerformance(shipments, from, to)
        );
        results.put(
                simulatedAnnealingOptimization.getAlgorithmName(),
                simulatedAnnealingOptimization.evaluatePerformance(shipments, from, to)
        );
        return results;
    }

    @Transactional(readOnly = true)
    public String benchmarkWinner(List<Shipment> shipments) {
        return benchmarkWinner(shipments, null, null);
    }

    @Transactional(readOnly = true)
    public String benchmarkWinner(List<Shipment> shipments, LocalDate from, LocalDate to) {
        Map<String, OptimizationResult> results = runBothAlgorithms(shipments, from, to);
        return results.values().stream()
                .max((a, b) -> {
                    int byCompleted = Double.compare(a.getCompletedPct(), b.getCompletedPct());
                    if (byCompleted != 0) return byCompleted;
                    int byTransit = Double.compare(b.getAvgTransitHours(), a.getAvgTransitHours());
                    if (byTransit != 0) return byTransit;
                    return Double.compare(b.getFlightUtilizationPct(), a.getFlightUtilizationPct());
                })
                .map(OptimizationResult::getAlgorithmName)
                .orElse("N/A");
    }

    @Transactional(readOnly = true)
    public Boolean isSystemCollapsed() {
        SimulationConfig config = getConfig();
        List<Airport> airports = airportRepository.findAll();
        if (airports.isEmpty()) return false;

        long critical = airports.stream()
                .filter(airport -> airport.getStatus(config.getNormalThresholdPct(), config.getWarningThresholdPct()) == AirportStatus.CRITICO)
                .count();

        return (critical * 100.0 / airports.size()) > 90.0;
    }

    @Transactional(readOnly = true)
    public Double getCollapseRisk() {
        SimulationConfig config = getConfig();
        List<Airport> airports = airportRepository.findAll();
        if (airports.isEmpty()) return 0.0;

        double avgOccupancy = airports.stream()
                .mapToDouble(Airport::getOccupancyPct)
                .average()
                .orElse(0.0) / 100.0;

        long criticalCount = airports.stream()
                .filter(airport -> airport.getStatus(config.getNormalThresholdPct(), config.getWarningThresholdPct()) == AirportStatus.CRITICO)
                .count();

        double criticalFraction = (double) criticalCount / airports.size();
        long activeShipments = shipmentRepository.countByStatusIn(List.of(ShipmentStatus.PENDING, ShipmentStatus.IN_ROUTE));
        long problematicShipments = shipmentRepository.countByStatusIn(List.of(ShipmentStatus.CRITICAL, ShipmentStatus.DELAYED));
        double problematicFraction = activeShipments == 0
                ? 0.0
                : (double) problematicShipments / (activeShipments + problematicShipments);

        double risk = (0.6 * avgOccupancy) + (0.3 * criticalFraction) + (0.1 * problematicFraction);
        return Math.min(1.0, risk);
    }

    @Transactional
    public Shipment markDelivered(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));
        LocalDateTime deliveredAt = LocalDateTime.now();

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        for (TravelStop stop : stops) {
            if (stop.getStopStatus() != StopStatus.COMPLETED) {
                stop.setStopStatus(StopStatus.COMPLETED);
                stop.setActualArrival(deliveredAt);
            }
        }
        if (!stops.isEmpty()) {
            travelStopRepository.saveAll(stops);
        }

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(deliveredAt);
        shipment.setProgressPercentage(100.0);
        Shipment saved = shipmentRepository.save(shipment);
        shipmentAuditService.log(
                saved,
                ShipmentAuditType.DELIVERED,
                "Entrega final confirmada manualmente",
                saved.getDestinationAirport(),
                null
        );
        return saved;
    }

    @Transactional
    public int repairDeliveredShipments() {
        int repaired = 0;
        for (Shipment shipment : shipmentRepository.findByStatus(ShipmentStatus.DELIVERED)) {
            List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
            boolean hasPending = stops.stream().anyMatch(stop -> stop.getStopStatus() != StopStatus.COMPLETED);
            boolean hasVisualResidue = !stops.isEmpty();
            if (!hasPending && !hasVisualResidue) {
                continue;
            }

            LocalDateTime deliveredAt = shipment.getDeliveredAt() != null ? shipment.getDeliveredAt() : LocalDateTime.now();
            for (TravelStop stop : stops) {
                if (stop.getStopStatus() != StopStatus.COMPLETED) {
                    stop.setStopStatus(StopStatus.COMPLETED);
                    stop.setActualArrival(deliveredAt);
                }
            }
            if (!stops.isEmpty()) {
                travelStopRepository.deleteAll(stops);
            }
            shipment.setProgressPercentage(100.0);
            if (shipment.getDeliveredAt() == null) {
                shipment.setDeliveredAt(deliveredAt);
            }
            shipmentRepository.save(shipment);
            repaired++;
        }
        return repaired;
    }

    @Transactional(readOnly = true)
    public double calculateProgress(Shipment shipment) {
        long totalStops = travelStopRepository.findByShipment(shipment).size();
        if (totalStops == 0) return 0.0;
        long completedStops = travelStopRepository.countCompletedByShipment(shipment);
        return (completedStops * 100.0) / totalStops;
    }

    private RouteOptimizer resolveOptimizer(String algorithmName) {
        if (algorithmName != null && antColonyOptimization.getAlgorithmName().equalsIgnoreCase(algorithmName)) {
            return antColonyOptimization;
        }
        if (algorithmName != null && simulatedAnnealingOptimization.getAlgorithmName().equalsIgnoreCase(algorithmName)) {
            return simulatedAnnealingOptimization;
        }
        if (algorithmName != null && "SA".equalsIgnoreCase(algorithmName.trim())) {
            return simulatedAnnealingOptimization;
        }
        return geneticAlgorithm;
    }

    private String getActiveAlgorithmName() {
        return switch (getConfig().getPrimaryAlgorithm()) {
            case ANT_COLONY -> antColonyOptimization.getAlgorithmName();
            case SIMULATED_ANNEALING -> simulatedAnnealingOptimization.getAlgorithmName();
            case GENETIC -> geneticAlgorithm.getAlgorithmName();
        };
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : configRepository.save(SimulationConfig.builder().build());
    }

    private void allocateFlightLoads(Shipment shipment, List<TravelStop> stops, boolean persistImmediately) {
        int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        java.util.Set<Flight> touched = new java.util.HashSet<>();
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null) continue;
            flight.setCurrentLoad(Math.min(flight.getMaxCapacity(), flight.getCurrentLoad() + luggage));
            touched.add(flight);
        }

        if (persistImmediately && !touched.isEmpty()) {
            flightRepository.saveAll(touched);
        }
    }

    private void releaseFlightLoads(Shipment shipment, List<TravelStop> stops, boolean persistImmediately) {
        int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        java.util.Set<Flight> touched = new java.util.HashSet<>();
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null) continue;
            flight.setCurrentLoad(Math.max(0, flight.getCurrentLoad() - luggage));
            touched.add(flight);
        }

        if (persistImmediately && !touched.isEmpty()) {
            flightRepository.saveAll(touched);
        }
    }

    private List<Flight> eligibleFlightsForShipment(Shipment shipment, List<Flight> flights) {
        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        int requiredLuggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();

        return flights.stream()
                .filter(flight -> flight.getStatus() == com.tasfb2b.model.FlightStatus.SCHEDULED)
                .filter(flight -> flight.getCurrentLoad() < flight.getMaxCapacity())
                .filter(flight -> (flight.getMaxCapacity() - flight.getCurrentLoad()) >= requiredLuggage)
                .filter(flight -> flight.getScheduledDeparture() != null)
                .filter(flight -> !flight.getScheduledDeparture().isBefore(registration))
                .toList();
    }

    private List<Flight> candidateFlightsForShipment(Shipment shipment, List<Flight> eligibleFlights) {
        if (eligibleFlights.isEmpty()) {
            return eligibleFlights;
        }

        Long originId = shipment.getOriginAirport() == null ? null : shipment.getOriginAirport().getId();
        Long destinationId = shipment.getDestinationAirport() == null ? null : shipment.getDestinationAirport().getId();
        if (originId == null || destinationId == null) {
            return eligibleFlights;
        }

        List<Flight> firstLegs = eligibleFlights.stream()
                .filter(flight -> flight.getOriginAirport().getId().equals(originId))
                .toList();
        if (firstLegs.isEmpty()) {
            return eligibleFlights;
        }

        Set<Long> hubIds = new HashSet<>();
        for (Flight first : firstLegs) {
            hubIds.add(first.getDestinationAirport().getId());
        }

        Set<Long> selectedFlightIds = new HashSet<>();
        List<Flight> reduced = new java.util.ArrayList<>();

        for (Flight flight : firstLegs) {
            if (selectedFlightIds.add(flight.getId())) {
                reduced.add(flight);
            }
        }
        for (Flight flight : eligibleFlights) {
            if (flight.getOriginAirport().getId().equals(originId)
                    && flight.getDestinationAirport().getId().equals(destinationId)
                    && selectedFlightIds.add(flight.getId())) {
                reduced.add(flight);
            }
        }
        for (Flight flight : eligibleFlights) {
            if (hubIds.contains(flight.getOriginAirport().getId())
                    && flight.getDestinationAirport().getId().equals(destinationId)
                    && selectedFlightIds.add(flight.getId())) {
                reduced.add(flight);
            }
        }

        List<Flight> base = reduced.isEmpty() ? eligibleFlights : reduced;
        return base.stream()
                .sorted(Comparator.comparing(Flight::getScheduledDeparture, Comparator.nullsLast(LocalDateTime::compareTo)))
                .limit(120)
                .toList();
    }

    private List<TravelStop> buildDirectCandidate(Shipment shipment, List<Flight> eligibleFlights) {
        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();

        return eligibleFlights.stream()
                .filter(flight -> flight.getOriginAirport().getId().equals(shipment.getOriginAirport().getId()))
                .filter(flight -> flight.getDestinationAirport().getId().equals(shipment.getDestinationAirport().getId()))
                .sorted(Comparator.comparing(Flight::getScheduledArrival, Comparator.nullsLast(LocalDateTime::compareTo)))
                .findFirst()
                .map(flight -> List.of(
                        TravelStop.builder()
                                .shipment(shipment)
                                .airport(shipment.getOriginAirport())
                                .flight(null)
                                .stopOrder(0)
                                .scheduledArrival(registration)
                                .stopStatus(StopStatus.PENDING)
                                .build(),
                        TravelStop.builder()
                                .shipment(shipment)
                                .airport(shipment.getDestinationAirport())
                                .flight(flight)
                                .stopOrder(1)
                                .scheduledArrival(flight.getScheduledArrival())
                                .stopStatus(StopStatus.PENDING)
                                .build()
                ))
                .orElse(List.of());
    }

    private CandidatePlan selectBestCandidate(Shipment shipment,
                                              List<TravelStop> directStops,
                                              List<TravelStop> multiHopStops) {
        CandidatePlan direct = evaluateCandidate(shipment, "DIRECT", directStops);
        CandidatePlan multi = evaluateCandidate(shipment, "MULTI_HOP", multiHopStops);

        if (direct.stops().isEmpty() && multi.stops().isEmpty()) {
            return direct;
        }
        if (direct.stops().isEmpty()) return multi;
        if (multi.stops().isEmpty()) return direct;

        return direct.score() <= multi.score()
                ? new CandidatePlan(direct.strategy(), direct.stops(), direct.score(), direct.etaHours(), multi.etaHours())
                : new CandidatePlan(multi.strategy(), multi.stops(), multi.score(), multi.etaHours(), direct.etaHours());
    }

    private CandidatePlan evaluateCandidate(Shipment shipment, String strategy, List<TravelStop> stops) {
        if (stops == null || stops.isEmpty()) {
            return new CandidatePlan(strategy, List.of(), Double.POSITIVE_INFINITY, null, null);
        }

        SimulationConfig config = getConfig();
        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        LocalDateTime finalArrival = stops.stream()
                .filter(stop -> stop.getFlight() != null)
                .map(stop -> stop.getFlight().getScheduledArrival())
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(registration.plusHours(36));

        double etaHours = Math.max(0.0, ChronoUnit.MINUTES.between(registration, finalArrival) / 60.0);
        double hopPenalty = Math.max(0, stops.size() - 2) * 0.75;
        double loadPenalty = stops.stream()
                .filter(stop -> stop.getFlight() != null)
                .map(TravelStop::getFlight)
                .mapToDouble(flight -> {
                    double load = flight.getLoadPct();
                    return load > 85.0 ? (load - 85.0) * 0.08 : 0.0;
                })
                .sum();
        double nodePenalty = stops.stream()
                .map(TravelStop::getAirport)
                .distinct()
                .mapToDouble(airport -> {
                    double occupancy = airport.getOccupancyPct();
                    return occupancy >= config.getWarningThresholdPct() ? 2.0 : 0.0;
                })
                .sum();
        double deadlinePenalty = 0.0;
        if (shipment.getDeadline() != null && finalArrival.isAfter(shipment.getDeadline())) {
            long hoursLate = Math.max(1L, ChronoUnit.HOURS.between(shipment.getDeadline(), finalArrival));
            deadlinePenalty = 500.0 + hoursLate * 25.0;
        }

        double score = etaHours + hopPenalty + loadPenalty + nodePenalty + deadlinePenalty;
        return new CandidatePlan(strategy, stops, score, etaHours, null);
    }

    private String buildPlanningMessage(String algorithmName, CandidatePlan selected) {
        String alt = selected.alternativeEtaHours() == null
                ? "sin alternativa"
                : String.format("ETA alternativa %.2fh", selected.alternativeEtaHours());
        return String.format(
                "Ruta planificada con %s (%s, %d tramos, ETA %.2fh, %s)",
                algorithmName,
                selected.strategy(),
                Math.max(0, selected.stops().size() - 1),
                selected.etaHours() == null ? 0.0 : selected.etaHours(),
                alt
        );
    }

    private record CandidatePlan(
            String strategy,
            List<TravelStop> stops,
            double score,
            Double etaHours,
            Double alternativeEtaHours
    ) {}
}
