package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SimulationEngineService {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngineService.class);

    private static final int ROUTE_PLANNING_BATCH_DAY_TO_DAY = 4;
    private static final int ROUTE_PLANNING_BATCH_PERIOD = 1;
    private static final int ROUTE_PLANNING_BATCH_COLLAPSE = 4;

    private final SimulationConfigRepository simulationConfigRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final AirportRepository airportRepository;
    private final ShipmentAuditService shipmentAuditService;
    private final SimulationRuntimeService runtimeService;
    private final RoutePlannerService routePlannerService;
    private final FlightScheduleService flightScheduleService;
    private final OperationalAlertService operationalAlertService;
    private final TransactionTemplate transactionTemplate;
    private final AtomicLong tickSequence = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean tickInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    public SimulationEngineService(
            SimulationConfigRepository simulationConfigRepository,
            FlightRepository flightRepository,
            ShipmentRepository shipmentRepository,
            TravelStopRepository travelStopRepository,
            AirportRepository airportRepository,
            ShipmentAuditService shipmentAuditService,
            SimulationRuntimeService runtimeService,
            RoutePlannerService routePlannerService,
            FlightScheduleService flightScheduleService,
            OperationalAlertService operationalAlertService,
            TransactionTemplate transactionTemplate
    ) {
        this.simulationConfigRepository = simulationConfigRepository;
        this.flightRepository = flightRepository;
        this.shipmentRepository = shipmentRepository;
        this.travelStopRepository = travelStopRepository;
        this.airportRepository = airportRepository;
        this.shipmentAuditService = shipmentAuditService;
        this.runtimeService = runtimeService;
        this.routePlannerService = routePlannerService;
        this.flightScheduleService = flightScheduleService;
        this.operationalAlertService = operationalAlertService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 1_000)
    public void tick() {
        try {
            runTickIfEligible();
        } catch (CannotAcquireLockException ex) {
            log.warn("Tick omitido por lock transitorio: {}", ex.getMessage());
        }
    }

    public void warmStartTick() {
        try {
            runTickIfEligible();
        } catch (CannotAcquireLockException ex) {
            log.warn("Warm start tick omitido por lock transitorio: {}", ex.getMessage());
        }
    }

    public void ensureProgressIfStale() {
        SimulationConfig config = getConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning()) || runtimeService.isPaused() || runtimeService.isResetting()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastTick = runtimeService.lastTickAt().orElse(null);
        if (lastTick != null && Duration.between(lastTick, now).toMillis() < 1500) {
            return;
        }

        try {
            runTickIfEligible();
        } catch (CannotAcquireLockException ex) {
            log.warn("Tick de recuperación omitido por lock transitorio: {}", ex.getMessage());
        }
    }

    private void runTickIfEligible() {
        if (!tickInProgress.compareAndSet(false, true)) {
            return;
        }
        SimulationConfig config = getConfig();
        try {
            if (!Boolean.TRUE.equals(config.getIsRunning()) || runtimeService.isPaused() || runtimeService.isResetting()) {
                return;
            }

            long startedAt = System.currentTimeMillis();
            transactionTemplate.executeWithoutResult(status -> executeTick(config));
            long elapsedMs = System.currentTimeMillis() - startedAt;
            if (elapsedMs > 1_000L) {
                log.warn("Tick de simulación tardó {} ms en escenario {}", elapsedMs, config.getScenario());
            }
        } finally {
            tickInProgress.set(false);
        }
    }

    public boolean isTickInProgress() {
        return tickInProgress.get();
    }

    public void resetTickSequence() {
        tickSequence.set(0L);
    }

    private void executeTick(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime simulatedNow = runtimeService.currentSimulationTime().orElseGet(() -> resolveInitialSimulationTime(now));
        long secondsAdvance = runtimeService.simulationSecondsPerTick(config);
        LocalDateTime horizon = simulatedNow.plusSeconds(secondsAdvance);

        planPendingShipments(config, horizon);
        activateFlights(horizon);
        closeFlightsAndAdvanceStops(horizon);
        markOverdueShipments(horizon);

        runtimeService.setSimulationTime(horizon);
        runtimeService.markTick(horizon);
    }

    private void planPendingShipments(SimulationConfig config, LocalDateTime horizon) {
        long tickNumber = tickSequence.incrementAndGet();
        if (!shouldPlanOnTick(config == null ? null : config.getScenario(), tickNumber)) {
            return;
        }

        int batchSize = planningBatchForScenario(config == null ? null : config.getScenario());
        LocalDateTime windowStart = horizon.toLocalDate().atStartOfDay();
        List<Shipment> pending = shipmentRepository.findPendingWithoutRouteForPlanningInWindow(windowStart, horizon, batchSize);
        if (pending.isEmpty()) {
            pending = shipmentRepository.findPendingWithoutRouteForPlanning(horizon, batchSize);
        }
        if (pending.isEmpty()) {
            return;
        }

        var airports = airportRepository.findAll();
        var availableFlights = flightScheduleService.availableFlightsForShipment(horizon.minusDays(1));

        for (Shipment shipment : pending) {
            try {
                routePlannerService.planShipment(
                        shipment,
                        "Genetic Algorithm",
                        availableFlights,
                        airports,
                        true
                );
            } catch (Exception ex) {
                log.debug("No se pudo planificar envio {} en tick: {}", shipment.getId(), ex.getMessage());
            }
        }
    }

    private int planningBatchForScenario(SimulationScenario scenario) {
        if (scenario == SimulationScenario.PERIOD_SIMULATION) {
            return ROUTE_PLANNING_BATCH_PERIOD;
        }
        if (scenario == SimulationScenario.COLLAPSE_TEST) {
            return ROUTE_PLANNING_BATCH_COLLAPSE;
        }
        return ROUTE_PLANNING_BATCH_DAY_TO_DAY;
    }

    private boolean shouldPlanOnTick(SimulationScenario scenario, long tickNumber) {
        long cadence;
        if (scenario == SimulationScenario.PERIOD_SIMULATION) {
            cadence = 12L;
        } else if (scenario == SimulationScenario.COLLAPSE_TEST) {
            cadence = 2L;
        } else {
            cadence = 1L;
        }
        return tickNumber % cadence == 1L;
    }

    private void activateFlights(LocalDateTime horizon) {
        List<Flight> eligibleFlights = flightRepository
                .findByStatusAndScheduledDepartureLessThanEqual(FlightStatus.SCHEDULED, horizon)
                .stream()
                .filter(flight -> flight.getScheduledArrival() == null || flight.getScheduledArrival().isAfter(horizon))
                .toList();

        List<TravelStop> pendingStops = eligibleFlights.isEmpty()
                ? List.of()
                : travelStopRepository.findByFlightInAndStopStatus(eligibleFlights, StopStatus.PENDING);
        java.util.Map<Long, List<TravelStop>> pendingStopsByFlightId = pendingStops.stream()
                .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getFlight().getId()));
        java.util.Map<Long, List<TravelStop>> allStopsByShipmentId = pendingStops.isEmpty()
                ? java.util.Map.of()
                : travelStopRepository.findByShipmentInOrderByShipmentIdAscStopOrderAsc(
                                pendingStops.stream().map(TravelStop::getShipment).distinct().toList())
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getShipment().getId()));

        List<Flight> toStart = eligibleFlights.stream()
                .filter(flight -> !pendingStopsByFlightId.getOrDefault(flight.getId(), List.of()).isEmpty())
                .toList();

        for (Flight flight : toStart) {
            flight.setStatus(FlightStatus.IN_FLIGHT);
            flightRepository.save(flight);

            List<TravelStop> stops = pendingStopsByFlightId.getOrDefault(flight.getId(), List.of());

            for (TravelStop stop : stops) {
                List<TravelStop> allStops = allStopsByShipmentId.getOrDefault(stop.getShipment().getId(), List.of());
                allStops.stream()
                        .filter(s -> s.getStopOrder() == 0 && s.getStopStatus() == StopStatus.PENDING)
                        .forEach(originStop -> {
                            originStop.setStopStatus(StopStatus.COMPLETED);
                            originStop.setActualArrival(flight.getScheduledDeparture() != null ? flight.getScheduledDeparture() : horizon);
                            travelStopRepository.save(originStop);
                        });

                stop.setStopStatus(StopStatus.IN_TRANSIT);
                travelStopRepository.save(stop);

                Shipment shipment = stop.getShipment();

                // Issue 7.1: release storage at the intermediate hub the shipment is departing from.
                // stopOrder >= 2 means the previous stop was a transit airport that received this
                // luggage (storage was incremented on arrival in closeFlightsAndAdvanceStops).
                if (stop.getStopOrder() != null && stop.getStopOrder() >= 2) {
                    TravelStop previousHub = allStops.stream()
                            .filter(s -> s.getStopOrder() != null && s.getStopOrder() == stop.getStopOrder() - 1)
                            .findFirst()
                            .orElse(null);
                    if (previousHub != null && previousHub.getAirport() != null) {
                        Airport hub = previousHub.getAirport();
                        int newLoad = Math.max(0, hub.getCurrentStorageLoad() - shipment.getLuggageCount());
                        hub.setCurrentStorageLoad(newLoad);
                        airportRepository.save(hub);
                    }
                }

                shipment.setStatus(ShipmentStatus.IN_ROUTE);
                shipmentRepository.save(shipment);

                audit(shipment, ShipmentAuditType.DEPARTED,
                        "Vuelo " + flight.getFlightCode() + " en curso hacia " + stop.getAirport().getIcaoCode(),
                        stop.getAirport(), flight.getFlightCode());
            }
        }
    }

    private void closeFlightsAndAdvanceStops(LocalDateTime horizon) {
        List<Flight> toComplete = flightRepository
                .findByStatusAndScheduledArrivalLessThanEqual(FlightStatus.IN_FLIGHT, horizon);

        List<TravelStop> impactedStops = toComplete.isEmpty()
                ? List.of()
                : travelStopRepository.findByFlightInAndStopStatus(toComplete, StopStatus.IN_TRANSIT);
        java.util.Map<Long, List<TravelStop>> impactedByFlightId = impactedStops.stream()
                .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getFlight().getId()));
        java.util.Map<Long, List<TravelStop>> allStopsByShipmentId = impactedStops.isEmpty()
                ? java.util.Map.of()
                : travelStopRepository.findByShipmentInOrderByShipmentIdAscStopOrderAsc(
                                impactedStops.stream().map(TravelStop::getShipment).distinct().toList())
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getShipment().getId()));

        for (Flight flight : toComplete) {
            flight.setStatus(FlightStatus.COMPLETED);
            flightRepository.save(flight);

            List<TravelStop> impacted = impactedByFlightId.getOrDefault(flight.getId(), List.of());

            for (TravelStop stop : impacted) {
                Shipment shipment = stop.getShipment();
                stop.setStopStatus(StopStatus.COMPLETED);
                stop.setActualArrival(horizon);
                travelStopRepository.save(stop);

                Airport airport = stop.getAirport();
                airport.setCurrentStorageLoad(Math.min(
                        airport.getMaxStorageCapacity(),
                        airport.getCurrentStorageLoad() + shipment.getLuggageCount()
                ));
                airportRepository.save(airport);

                audit(shipment, ShipmentAuditType.ARRIVED,
                        "Arribo a " + airport.getIcaoCode() + " mediante " + flight.getFlightCode(),
                        airport, flight.getFlightCode());

                List<TravelStop> allStops = allStopsByShipmentId.getOrDefault(shipment.getId(), List.of());
                boolean allDone = allStops.stream().allMatch(s -> s.getStopStatus() == StopStatus.COMPLETED);
                if (allDone) {
                    shipment.setStatus(ShipmentStatus.DELIVERED);
                    shipment.setDeliveredAt(horizon);
                    shipment.setProgressPercentage(100.0);
                    shipmentRepository.save(shipment);

                    // Issue 7.1: release destination storage — shipment handed to receiver,
                    // no longer occupying the airport's warehouse.
                    int destLoad = Math.max(0, airport.getCurrentStorageLoad() - shipment.getLuggageCount());
                    airport.setCurrentStorageLoad(destLoad);
                    airportRepository.save(airport);

                    audit(shipment, ShipmentAuditType.DELIVERED,
                            "Envio entregado en destino", airport, flight.getFlightCode());
                } else {
                    double progress = allStops.isEmpty()
                            ? 0.0
                            : (allStops.stream().filter(s -> s.getStopStatus() == StopStatus.COMPLETED).count() * 100.0) / allStops.size();
                    shipment.setProgressPercentage(progress);
                    shipmentRepository.save(shipment);
                }
            }
        }
    }

    private void markOverdueShipments(LocalDateTime now) {
        int marked = shipmentRepository.markActiveAsDelayedBefore(now);
        if (marked <= 0) {
            return;
        }
        shipmentRepository.findByStatus(ShipmentStatus.DELAYED).stream()
                .filter(shipment -> shipment.getDeadline() != null && shipment.getDeadline().isBefore(now))
                .limit(Math.max(10, marked))
                .forEach(shipment -> operationalAlertService.ensureShipmentAlert(
                        shipment,
                        "OVERDUE_SHIPMENT",
                        "El envío excedió su plazo operativo y quedó marcado como delayed"
                ));
    }

    private void audit(Shipment shipment,
                       ShipmentAuditType type,
                       String message,
                       Airport airport,
                       String flightCode) {
        shipmentAuditService.log(shipment, type, message, airport, flightCode);
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : simulationConfigRepository.save(SimulationConfig.builder().build());
    }

    private LocalDateTime resolveInitialSimulationTime(LocalDateTime fallbackNow) {
        // Prefer configured scenario start, then projected demand start
        SimulationConfig config = getConfig();
        if (config.getScenarioStartAt() != null) {
            return config.getScenarioStartAt();
        }
        if (config.getProjectedFrom() != null) {
            return config.getProjectedFrom().atStartOfDay();
        }
        LocalDateTime minDeparture = flightRepository.findMinScheduledDeparture();
        if (minDeparture != null) {
            return minDeparture.minusMinutes(5);
        }
        LocalDateTime min = shipmentRepository.findMinRegistrationDate();
        return min == null ? fallbackNow : min;
    }
}
