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
import com.tasfb2b.service.algorithm.FastRoutePlanning;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import com.tasfb2b.service.algorithm.OptimizationResult;
import com.tasfb2b.service.algorithm.RoutePlanningSupport;
import com.tasfb2b.service.algorithm.RouteOptimizer;
import com.tasfb2b.service.algorithm.SimulatedAnnealingOptimization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
                true,
                true);
    }

    public List<TravelStop> planShipment(Shipment shipment,
                                         String algorithmName,
                                         List<Flight> availableFlights,
                                         List<Airport> airports,
                                         boolean persistFlightLoadImmediately) {
        return planShipment(shipment, algorithmName, availableFlights, airports, persistFlightLoadImmediately, true);
    }

    public List<TravelStop> planShipment(Shipment shipment,
                                         String algorithmName,
                                         List<Flight> availableFlights,
                                         List<Airport> airports,
                                         boolean persistFlightLoadImmediately,
                                         boolean recordPlanningAudit) {
        return planShipment(shipment, algorithmName, availableFlights, airports, persistFlightLoadImmediately, recordPlanningAudit, false);
    }

    public List<TravelStop> planShipment(Shipment shipment,
                                         String algorithmName,
                                         List<Flight> availableFlights,
                                         List<Airport> airports,
                                         boolean persistFlightLoadImmediately,
                                         boolean recordPlanningAudit,
                                         boolean fastPlanningMode) {
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlights(shipment, availableFlights);
        PlanningContext planning = fastPlanningMode
                ? prepareBootstrapPlanningContext(shipment, eligibleFlights)
                : preparePlanningContext(
                        shipment,
                        eligibleFlights,
                        RoutePlanningSupport.planningCandidatesFromEligible(shipment, eligibleFlights)
                );
        return planShipment(shipment, algorithmName, airports, persistFlightLoadImmediately, recordPlanningAudit, fastPlanningMode, planning);
    }

    public List<TravelStop> planShipment(Shipment shipment,
                                         String algorithmName,
                                         RoutePlanningSupport.PlanningFlightIndex flightIndex,
                                         List<Airport> airports,
                                         boolean persistFlightLoadImmediately,
                                         boolean recordPlanningAudit,
                                         boolean fastPlanningMode) {
        PlanningContext planning = fastPlanningMode
                ? prepareBootstrapPlanningContext(shipment, flightIndex)
                : preparePlanningContext(shipment, flightIndex);
        return planShipment(shipment, algorithmName, airports, persistFlightLoadImmediately, recordPlanningAudit, fastPlanningMode, planning);
    }

    public PlannedShipment classifyAndPlanShipment(Shipment shipment,
                                                   String algorithmName,
                                                   List<Airport> airports,
                                                   boolean persistFlightLoadImmediately,
                                                   boolean recordPlanningAudit,
                                                   boolean fastPlanningMode,
                                                   List<Flight> availableFlights) {
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlights(shipment, availableFlights);
        PlanningContext planning = fastPlanningMode
                ? prepareBootstrapPlanningContext(shipment, eligibleFlights)
                : preparePlanningContext(
                        shipment,
                        eligibleFlights,
                        RoutePlanningSupport.planningCandidatesFromEligible(shipment, eligibleFlights)
                );
        List<TravelStop> stops = planShipment(
                shipment,
                algorithmName,
                airports,
                persistFlightLoadImmediately,
                recordPlanningAudit,
                fastPlanningMode,
                planning
        );
        return new PlannedShipment(planning.difficulty(), stops);
    }

    private List<TravelStop> planShipment(Shipment shipment,
                                          String algorithmName,
                                          List<Airport> airports,
                                          boolean persistFlightLoadImmediately,
                                          boolean recordPlanningAudit,
                                          boolean fastPlanningMode,
                                          PlanningContext planning) {
        RouteOptimizer optimizer = resolveOptimizer(algorithmName);

        List<TravelStop> stops;
        CandidatePlan selected;
        if (fastPlanningMode) {
            selected = selectBootstrapCandidate(shipment, planning);
            stops = selected.stops();
        } else {
            CandidatePlan gaCandidate = planning.fastPathCandidate() != null
                    ? new CandidatePlan("MULTI_HOP_SKIPPED", List.of(), Double.POSITIVE_INFINITY, null, null)
                    : evaluateCandidate(shipment, "MULTI_HOP", optimizer.planRouteFromCandidates(shipment, planning.candidateFlights(), airports));
            selected = planning.fastPathCandidate() != null
                    ? planning.fastPathCandidate()
                    : selectBestCandidate(planning.directCandidate(), gaCandidate);
            stops = selected.stops();
        }

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

        if (recordPlanningAudit) {
            shipmentAuditService.log(
                    shipment,
                    ShipmentAuditType.ROUTE_PLANNED,
                    buildPlanningMessage(algorithmName, selected),
                    shipment.getOriginAirport(),
                    null
            );
        }

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
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlights(shipment, availableFlights);
        List<Flight> candidateFlights = RoutePlanningSupport.planningCandidatesFromEligible(shipment, eligibleFlights);
        PlanningContext planning = preparePlanningContext(shipment, eligibleFlights, candidateFlights);
        if (planning.fastPathCandidate() != null) {
            return planning.fastPathCandidate().stops();
        }
        CandidatePlan gaCandidate = evaluateCandidate(shipment, "MULTI_HOP", optimizer.planRouteFromCandidates(shipment, candidateFlights, airports));
        return selectBestCandidate(planning.directCandidate(), gaCandidate).stops();
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

    public List<Flight> availableFlightsForWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        LocalDateTime from = fromInclusive == null ? LocalDateTime.now() : fromInclusive;
        LocalDateTime to = toExclusive == null || !toExclusive.isAfter(from) ? from.plusDays(3) : toExclusive;
        flightScheduleService.ensureFlightsForWindow(from, to);
        return flightRepository.findSchedulableFlightsBetween(from, to);
    }

    @Transactional(readOnly = true)
    public List<Flight> schedulableFlightsForExistingWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        LocalDateTime from = fromInclusive == null ? LocalDateTime.now() : fromInclusive;
        LocalDateTime to = toExclusive == null || !toExclusive.isAfter(from) ? from.plusDays(3) : toExclusive;
        return flightScheduleService.schedulableFlightsWithinWindow(from, to);
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
        List<Shipment> filtered = shipments == null ? List.of() : shipments.stream()
                .filter(shipment -> withinPeriod(shipment, from, to))
                .sorted(Comparator.comparing(Shipment::getRegistrationDate, Comparator.nullsLast(LocalDateTime::compareTo)))
                .limit(220)
                .toList();
        List<Airport> airports = airportRepository.findAll();
        List<Flight> availableFlights = flightRepository.findFlightsWithAvailableCapacity();
        return benchmarkGaVsAco(filtered, availableFlights, airports);
    }

    @Transactional(readOnly = true)
    public Map<String, OptimizationResult> benchmarkGaVsAco(List<Shipment> shipments,
                                                            List<Flight> availableFlights,
                                                            List<Airport> airports) {
        List<Shipment> filtered = shipments == null ? List.of() : shipments;

        Map<String, OptimizationResult> results = new HashMap<>();
        results.put(geneticAlgorithm.getAlgorithmName(), benchmarkAlgorithm(geneticAlgorithm, filtered, availableFlights, airports));
        results.put(antColonyOptimization.getAlgorithmName(), benchmarkAlgorithm(antColonyOptimization, filtered, availableFlights, airports));
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

    private OptimizationResult benchmarkAlgorithm(RouteOptimizer optimizer,
                                                  List<Shipment> shipments,
                                                  List<Flight> availableFlights,
                                                  List<Airport> airports) {
        if (shipments == null || shipments.isEmpty()) {
            return OptimizationResult.builder()
                    .algorithmName(optimizer.getAlgorithmName())
                    .completedShipments(0)
                    .completedPct(0.0)
                    .avgTransitHours(0.0)
                    .totalReplanning(0)
                    .operationalCost(0.0)
                    .flightUtilizationPct(0.0)
                    .saturatedAirports(0)
                    .collapseReachedAt(null)
                    .build();
        }

        int total = shipments.size();
        int feasible = 0;
        int misses = 0;
        double transitHoursSum = 0.0;
        double operationalCost = 0.0;
        double utilizationSum = 0.0;
        int saturatedAirports = 0;
        LocalDateTime collapseReachedAt = null;
        java.util.Set<Long> touchedAirports = new java.util.HashSet<>();

        for (Shipment shipment : shipments) {
            List<TravelStop> stops = optimizer.planRoute(shipment, availableFlights, airports);
            if (stops.isEmpty()) {
                misses++;
                if (collapseReachedAt == null) {
                    collapseReachedAt = shipment.getRegistrationDate();
                }
                continue;
            }

            feasible++;
            LocalDateTime arrival = stops.stream()
                    .filter(stop -> stop.getFlight() != null)
                    .map(stop -> stop.getFlight().getScheduledArrival())
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(shipment.getRegistrationDate());

            double etaHours = Math.max(0.0, ChronoUnit.MINUTES.between(
                    shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate(),
                    arrival == null ? LocalDateTime.now() : arrival
            ) / 60.0);
            transitHoursSum += etaHours;
            if (shipment.getDeadline() != null && arrival != null && arrival.isAfter(shipment.getDeadline())) {
                misses++;
            }

            int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
            operationalCost += luggage * 0.25 + etaHours * 1.3 + Math.max(0, stops.size() - 2) * 7.0;
            utilizationSum += stops.stream()
                    .filter(stop -> stop.getFlight() != null)
                    .map(TravelStop::getFlight)
                    .mapToDouble(Flight::getLoadPct)
                    .average()
                    .orElse(0.0);

            for (TravelStop stop : stops) {
                Airport airport = stop.getAirport();
                if (airport != null && touchedAirports.add(airport.getId()) && airport.getOccupancyPct() >= getConfig().getWarningThresholdPct()) {
                    saturatedAirports++;
                }
            }
        }

        double completedPct = feasible == 0 ? 0.0 : feasible * 100.0 / total;
        return OptimizationResult.builder()
                .algorithmName(optimizer.getAlgorithmName())
                .completedShipments(feasible)
                .completedPct(completedPct)
                .avgTransitHours(feasible == 0 ? 0.0 : transitHoursSum / feasible)
                .totalReplanning(Math.max(0, total - feasible))
                .operationalCost(total == 0 ? 0.0 : operationalCost)
                .flightUtilizationPct(feasible == 0 ? 0.0 : utilizationSum / feasible)
                .saturatedAirports(saturatedAirports)
                .collapseReachedAt(collapseReachedAt)
                .build();
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        if (shipment == null || shipment.getRegistrationDate() == null) return true;
        LocalDate day = shipment.getRegistrationDate().toLocalDate();
        boolean afterFrom = from == null || !day.isBefore(from);
        boolean beforeTo = to == null || !day.isAfter(to);
        return afterFrom && beforeTo;
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
        java.util.Map<Long, Integer> deltasByFlightId = new java.util.LinkedHashMap<>();
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null) continue;
            if (persistImmediately) {
                if (flight.getId() != null) {
                    deltasByFlightId.merge(flight.getId(), luggage, Integer::sum);
                }
            } else {
                flight.setReservedLoad(Math.min(flight.getMaxCapacity(), flight.getReservedLoad() + luggage));
            }
        }

        if (persistImmediately) {
            for (Map.Entry<Long, Integer> entry : deltasByFlightId.entrySet()) {
                int updated = flightRepository.reserveCapacityIfAvailable(entry.getKey(), entry.getValue());
                if (updated <= 0) {
                    throw new IllegalStateException("Capacidad insuficiente al reservar vuelo " + entry.getKey());
                }
            }
        }
    }

    private void releaseFlightLoads(Shipment shipment, List<TravelStop> stops, boolean persistImmediately) {
        int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
        java.util.Map<Long, Integer> deltasByFlightId = new java.util.LinkedHashMap<>();
        for (TravelStop stop : stops) {
            Flight flight = stop.getFlight();
            if (flight == null) continue;
            if (persistImmediately) {
                if (flight.getId() != null) {
                    deltasByFlightId.merge(flight.getId(), luggage, Integer::sum);
                }
            } else {
                flight.setReservedLoad(Math.max(0, flight.getReservedLoad() - luggage));
            }
        }

        if (persistImmediately) {
            for (Map.Entry<Long, Integer> entry : deltasByFlightId.entrySet()) {
                flightRepository.releaseReservedCapacity(entry.getKey(), entry.getValue());
            }
        }
    }

    private List<TravelStop> buildDirectCandidate(Shipment shipment, Map<Long, List<Flight>> eligibleFlightsByOrigin) {
        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        Long originId = shipment.getOriginAirport() == null ? null : shipment.getOriginAirport().getId();
        Long destinationId = shipment.getDestinationAirport() == null ? null : shipment.getDestinationAirport().getId();
        if (originId == null || destinationId == null) {
            return List.of();
        }

        return eligibleFlightsByOrigin.getOrDefault(originId, List.of()).stream()
                .filter(flight -> flight.getDestinationAirport() != null)
                .filter(flight -> destinationId.equals(flight.getDestinationAirport().getId()))
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

    private CandidatePlan buildDominantOneHopCandidate(Shipment shipment, Map<Long, List<Flight>> eligibleFlightsByOrigin) {
        if (shipment == null || eligibleFlightsByOrigin == null || eligibleFlightsByOrigin.isEmpty()) {
            return new CandidatePlan("ONE_HOP", List.of(), Double.POSITIVE_INFINITY, null, null);
        }

        LocalDateTime registration = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        Long originId = shipment.getOriginAirport() == null ? null : shipment.getOriginAirport().getId();
        Long destinationId = shipment.getDestinationAirport() == null ? null : shipment.getDestinationAirport().getId();
        if (originId == null || destinationId == null) {
            return new CandidatePlan("ONE_HOP", List.of(), Double.POSITIVE_INFINITY, null, null);
        }

        List<Flight> firstLegs = eligibleFlightsByOrigin.getOrDefault(originId, List.of()).stream()
                .limit(16)
                .toList();

        CandidatePlan best = new CandidatePlan("ONE_HOP", List.of(), Double.POSITIVE_INFINITY, null, null);
        CandidatePlan second = new CandidatePlan("ONE_HOP_ALT", List.of(), Double.POSITIVE_INFINITY, null, null);
        for (Flight first : firstLegs) {
            if (first.getDestinationAirport() == null || first.getDestinationAirport().getId() == null) {
                continue;
            }
            LocalDateTime minDeparture = first.getScheduledArrival() == null ? registration : first.getScheduledArrival().plusMinutes(30);
            List<Flight> secondLegs = eligibleFlightsByOrigin.getOrDefault(first.getDestinationAirport().getId(), List.of()).stream()
                    .filter(flight -> flight.getDestinationAirport() != null)
                    .filter(flight -> destinationId.equals(flight.getDestinationAirport().getId()))
                    .filter(flight -> flight.getScheduledDeparture() != null && !flight.getScheduledDeparture().isBefore(minDeparture))
                    .limit(4)
                    .toList();
            for (Flight secondLeg : secondLegs) {
                CandidatePlan candidate = evaluateCandidate(shipment, "ONE_HOP", toStops(shipment, registration, List.of(first, secondLeg)));
                if (candidate.score() < best.score()) {
                    second = best;
                    best = candidate;
                } else if (candidate.score() < second.score()) {
                    second = candidate;
                }
            }
        }

        if (best.stops().isEmpty()) {
            return best;
        }
        return new CandidatePlan(best.strategy(), best.stops(), best.score(), best.etaHours(),
                Double.isFinite(second.score()) ? second.etaHours() : null);
    }

    public ShipmentDifficulty classifyShipmentDifficulty(Shipment shipment, List<Flight> availableFlights) {
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlights(shipment, availableFlights);
        List<Flight> candidateFlights = RoutePlanningSupport.planningCandidatesFromEligible(shipment, eligibleFlights);
        PlanningContext planning = preparePlanningContext(shipment, eligibleFlights, candidateFlights);
        return planning.difficulty();
    }

    private PlanningContext preparePlanningContext(Shipment shipment,
                                                   RoutePlanningSupport.PlanningFlightIndex flightIndex) {
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlightsFromIndex(shipment, flightIndex);
        List<Flight> candidateFlights = RoutePlanningSupport.planningCandidatesFromIndex(shipment, flightIndex);
        return preparePlanningContext(shipment, eligibleFlights, candidateFlights);
    }

    private PlanningContext preparePlanningContext(Shipment shipment,
                                                   List<Flight> eligibleFlights,
                                                   List<Flight> candidateFlights) {
        Map<Long, List<Flight>> eligibleFlightsByOrigin = RoutePlanningSupport.planningIndexByOrigin(eligibleFlights);
        CandidatePlan directCandidate = evaluateCandidate(shipment, "DIRECT", buildDirectCandidate(shipment, eligibleFlightsByOrigin));
        CandidatePlan oneHopCandidate = buildDominantOneHopCandidate(shipment, eligibleFlightsByOrigin);
        CandidatePlan fastPathCandidate = chooseFastPathCandidate(directCandidate, oneHopCandidate);
        return new PlanningContext(eligibleFlights, candidateFlights, directCandidate, oneHopCandidate, fastPathCandidate, difficultyFor(candidateFlights.size(), fastPathCandidate));
    }

    private PlanningContext prepareBootstrapPlanningContext(Shipment shipment,
                                                           List<Flight> eligibleFlights) {
        Map<Long, List<Flight>> eligibleFlightsByOrigin = RoutePlanningSupport.planningIndexByOrigin(eligibleFlights);
        CandidatePlan directCandidate = evaluateCandidate(shipment, "DIRECT", buildDirectCandidate(shipment, eligibleFlightsByOrigin));
        CandidatePlan oneHopCandidate = buildDominantOneHopCandidate(shipment, eligibleFlightsByOrigin);
        CandidatePlan fastPathCandidate = chooseFastPathCandidate(directCandidate, oneHopCandidate);
        List<Flight> candidateFlights = directCandidate.stops().isEmpty() && oneHopCandidate.stops().isEmpty()
                ? RoutePlanningSupport.planningCandidatesFromEligible(shipment, eligibleFlights)
                : List.of();
        return new PlanningContext(
                eligibleFlights,
                candidateFlights,
                directCandidate,
                oneHopCandidate,
                fastPathCandidate,
                difficultyFor(candidateFlights.size(), fastPathCandidate)
        );
    }

    private PlanningContext prepareBootstrapPlanningContext(Shipment shipment,
                                                           RoutePlanningSupport.PlanningFlightIndex flightIndex) {
        List<Flight> eligibleFlights = RoutePlanningSupport.eligiblePlanningFlightsFromIndex(shipment, flightIndex);
        Map<Long, List<Flight>> eligibleFlightsByOrigin = RoutePlanningSupport.planningIndexByOrigin(eligibleFlights);
        CandidatePlan directCandidate = evaluateCandidate(shipment, "DIRECT", buildDirectCandidate(shipment, eligibleFlightsByOrigin));
        CandidatePlan oneHopCandidate = buildDominantOneHopCandidate(shipment, eligibleFlightsByOrigin);
        CandidatePlan fastPathCandidate = chooseFastPathCandidate(directCandidate, oneHopCandidate);
        List<Flight> candidateFlights = directCandidate.stops().isEmpty() && oneHopCandidate.stops().isEmpty()
                ? RoutePlanningSupport.planningCandidatesFromIndex(shipment, flightIndex)
                : List.of();
        return new PlanningContext(
                eligibleFlights,
                candidateFlights,
                directCandidate,
                oneHopCandidate,
                fastPathCandidate,
                difficultyFor(candidateFlights.size(), fastPathCandidate)
        );
    }

    @Transactional(readOnly = true)
    public String activeAlgorithmName() {
        return getActiveAlgorithmName();
    }

    private CandidatePlan selectBootstrapCandidate(Shipment shipment,
                                                   PlanningContext planning) {
        CandidatePlan bestCheapCandidate = selectBestCandidate(planning.directCandidate(), planning.oneHopCandidate());
        if (!bestCheapCandidate.stops().isEmpty()) {
            return bestCheapCandidate;
        }
        List<TravelStop> stops = FastRoutePlanning.planBestEffort(shipment, planning.candidateFlights());
        CandidatePlan bootstrapCandidate = evaluateCandidate(shipment, "FAST_BOOTSTRAP", stops);
        return selectBestCandidate(bestCheapCandidate, bootstrapCandidate);
    }

    private ShipmentDifficulty difficultyFor(int candidateCount, CandidatePlan fastPathCandidate) {
        if (fastPathCandidate != null) {
            return fastPathCandidate.stops().size() == 2 ? ShipmentDifficulty.TRIVIAL_DIRECT : ShipmentDifficulty.TRIVIAL_ONE_HOP;
        }
        if (candidateCount <= 16) {
            return ShipmentDifficulty.LIGHT_GA;
        }
        return candidateCount <= 48 ? ShipmentDifficulty.STANDARD_GA : ShipmentDifficulty.COMPLEX_GA;
    }

    private CandidatePlan chooseFastPathCandidate(CandidatePlan direct, CandidatePlan oneHop) {
        if (isStrongFastPath(direct)) {
            return direct;
        }
        if (isDominantOneHop(oneHop)) {
            return oneHop;
        }
        return null;
    }

    private boolean isStrongFastPath(CandidatePlan candidate) {
        return candidate != null
                && !candidate.stops().isEmpty()
                && candidate.stops().size() == 2
                && candidate.score() <= 12.0
                && candidate.alternativeEtaHours() == null;
    }

    private boolean isDominantOneHop(CandidatePlan candidate) {
        if (candidate == null || candidate.stops().isEmpty() || candidate.stops().size() != 3) {
            return false;
        }
        if (candidate.etaHours() == null) {
            return false;
        }
        if (candidate.alternativeEtaHours() == null) {
            return candidate.score() <= 20.0;
        }
        return candidate.score() <= 24.0 && (candidate.alternativeEtaHours() - candidate.etaHours()) >= 4.0;
    }

    private CandidatePlan selectBestCandidate(CandidatePlan direct,
                                              CandidatePlan multi) {
        if (direct.stops().isEmpty() && multi.stops().isEmpty()) {
            return direct;
        }
        if (direct.stops().isEmpty()) return multi;
        if (multi.stops().isEmpty()) return direct;

        return direct.score() <= multi.score()
                ? new CandidatePlan(direct.strategy(), direct.stops(), direct.score(), direct.etaHours(), multi.etaHours())
                : new CandidatePlan(multi.strategy(), multi.stops(), multi.score(), multi.etaHours(), direct.etaHours());
    }

    private List<TravelStop> toStops(Shipment shipment, LocalDateTime registration, List<Flight> route) {
        List<TravelStop> stops = new java.util.ArrayList<>();
        stops.add(TravelStop.builder()
                .shipment(shipment)
                .airport(shipment.getOriginAirport())
                .flight(null)
                .stopOrder(0)
                .scheduledArrival(registration)
                .stopStatus(StopStatus.PENDING)
                .build());
        for (int i = 0; i < route.size(); i++) {
            Flight flight = route.get(i);
            stops.add(TravelStop.builder()
                    .shipment(shipment)
                    .airport(flight.getDestinationAirport())
                    .flight(flight)
                    .stopOrder(i + 1)
                    .scheduledArrival(flight.getScheduledArrival())
                    .stopStatus(StopStatus.PENDING)
                    .build());
        }
        return stops;
    }

    private record PlanningContext(
            List<Flight> eligibleFlights,
            List<Flight> candidateFlights,
            CandidatePlan directCandidate,
            CandidatePlan oneHopCandidate,
            CandidatePlan fastPathCandidate,
            ShipmentDifficulty difficulty
    ) {}

    public record PlannedShipment(ShipmentDifficulty difficulty, List<TravelStop> stops) {}

    public enum ShipmentDifficulty {
        TRIVIAL_DIRECT,
        TRIVIAL_ONE_HOP,
        LIGHT_GA,
        STANDARD_GA,
        COMPLEX_GA
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
