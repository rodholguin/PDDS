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

    private final SimulationConfigRepository simulationConfigRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final AirportRepository airportRepository;
    private final ShipmentAuditService shipmentAuditService;
    private final SimulationRuntimeService runtimeService;
    private final TransactionTemplate transactionTemplate;
    private final AtomicLong tickSequence = new AtomicLong();
    private final AtomicLong nextEligibleTickAtMs = new AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicBoolean tickInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    public SimulationEngineService(
            SimulationConfigRepository simulationConfigRepository,
            FlightRepository flightRepository,
            ShipmentRepository shipmentRepository,
            TravelStopRepository travelStopRepository,
            AirportRepository airportRepository,
            ShipmentAuditService shipmentAuditService,
            SimulationRuntimeService runtimeService,
            TransactionTemplate transactionTemplate
    ) {
        this.simulationConfigRepository = simulationConfigRepository;
        this.flightRepository = flightRepository;
        this.shipmentRepository = shipmentRepository;
        this.travelStopRepository = travelStopRepository;
        this.airportRepository = airportRepository;
        this.shipmentAuditService = shipmentAuditService;
        this.runtimeService = runtimeService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 250)
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
        long staleThresholdMs = Math.max(runtimeService.tickIntervalMs(config) * 3L, 1_500L);
        if (lastTick != null && Duration.between(lastTick, now).toMillis() < staleThresholdMs) {
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

            LocalDateTime now = LocalDateTime.now();
            long tickIntervalMs = runtimeService.tickIntervalMs(config);
            long nowMs = System.currentTimeMillis();
            long nextEligibleAt = nextEligibleTickAtMs.get();
            if (nextEligibleAt > nowMs) {
                return;
            }
            nextEligibleTickAtMs.set(nowMs + tickIntervalMs);

            long startedAt = nowMs;
            transactionTemplate.executeWithoutResult(status -> executeTick(config));
            long elapsedMs = System.currentTimeMillis() - startedAt;
            if (elapsedMs > tickIntervalMs) {
                log.warn("Tick de simulación tardó {} ms en escenario {} (intervalo objetivo={} ms)", elapsedMs, config.getScenario(), tickIntervalMs);
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
        nextEligibleTickAtMs.set(0L);
    }

    public void executeHeadlessTick(SimulationConfig config) {
        transactionTemplate.executeWithoutResult(status -> executeTick(config));
    }

    void executeTick(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime simulatedNow = runtimeService.currentSimulationTime().orElseGet(() -> resolveInitialSimulationTime(now));
        long secondsAdvance = runtimeService.simulationSecondsPerTick(config);
        LocalDateTime horizon = simulatedNow.plusSeconds(secondsAdvance);
        LocalDateTime periodEnd = resolvePeriodEnd(config);
        LocalDateTime effectiveHorizon = periodEnd != null && horizon.isAfter(periodEnd) ? periodEnd : horizon;
        LocalDateTime planningHorizon = effectiveHorizon;
        // Plan-ahead guard (PERIOD_SIMULATION y COLLAPSE_TEST): el reloj no adelanta más allá de lo
        // ya planificado (plannedThrough). Como los envíos inviables quedan CRITICAL (excluidos del
        // conteo de "sin ruta"), no hay deadlock y el colapso refleja envíos que de verdad no llegan.
        if (config.getScenario() == SimulationScenario.PERIOD_SIMULATION
                || config.getScenario() == SimulationScenario.COLLAPSE_TEST) {
            LocalDateTime plannedThrough = runtimeService.periodPlannedThrough().orElse(null);
            if (plannedThrough != null && plannedThrough.isBefore(effectiveHorizon)) {
                runtimeService.recordPeriodTickWait(effectiveHorizon, plannedThrough);
                log.info("Tick plan-ahead en espera: escenario={} horizon={} plannedThrough={}", config.getScenario(), effectiveHorizon, plannedThrough);
                return;
            }
        }

        long tickStartedAt = System.nanoTime();
        reconcileFlightStates(simulatedNow, effectiveHorizon);
        long afterReconcile = System.nanoTime();
        // Procesar TODAS las piernas que caen dentro del salto de reloj. A alta velocidad un envío
        // multi-tramo puede recorrer varias piernas en un mismo salto; cada iteración avanza una pierna
        // (activa el tramo cuyo previo ya terminó + cierra los que arribaron). Se repite mientras haya
        // progreso, con cota de seguridad para no bloquear el tick.
        for (int legPass = 0; legPass < 50; legPass++) {
            int activated = activateFlights(simulatedNow, effectiveHorizon);
            int completed = closeFlightsAndAdvanceStops(simulatedNow, effectiveHorizon);
            if (activated + completed == 0) {
                break;
            }
        }
        long afterActivate = System.nanoTime();
        long afterClose = afterActivate;

        runtimeService.setSimulationTime(effectiveHorizon);
        long afterSetTime = System.nanoTime();
        runtimeService.markTick(effectiveHorizon);
        long afterMarkTick = System.nanoTime();

        long totalTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterMarkTick - tickStartedAt);
        runtimeService.recordTickElapsed(totalTickMs);
        long targetTickMs = runtimeService.tickIntervalMs(config);
        if (totalTickMs > targetTickMs) {
            log.warn(
                "Tick breakdown escenario={} total={} ms reconcile={} ms plan={} ms activate={} ms close={} ms overdue={} ms setTime={} ms markTick={} ms",
                config == null ? null : config.getScenario(),
                totalTickMs,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterReconcile - tickStartedAt),
                0L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterActivate - afterReconcile),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterClose - afterActivate),
                0L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterSetTime - afterClose),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(afterMarkTick - afterSetTime)
            );
        }

        // Colapso (COLLAPSE_TEST): si el primer envío ya no llega a tiempo, registrar y detener.
        if (config.getScenario() == SimulationScenario.COLLAPSE_TEST
                && config.getCollapseDetectedAtSim() == null
                && detectCollapse(config, effectiveHorizon)) {
            return;
        }

        if (periodEnd != null && !effectiveHorizon.isBefore(periodEnd)) {
            runtimeService.stopSimulationOnly();
            log.info("Simulacion de periodo finalizada automaticamente en {}", effectiveHorizon);
        }
    }

    private void reconcileFlightStates(LocalDateTime simulatedNow, LocalDateTime horizon) {
        // Incluye vuelos que despegan Y aterrizan dentro del mismo salto de reloj (arrival > simulatedNow),
        // no solo los que siguen volando al final; así a alta velocidad ningún vuelo corto queda sin
        // procesar (se activa + completa en el mismo tick) y no se generan colapsos falsos.
        List<Long> recoverableScheduledIds = flightRepository.findRecoverableScheduledFlightIds(simulatedNow, horizon);
        if (!recoverableScheduledIds.isEmpty()) {
            flightRepository.updateStatusByIds(recoverableScheduledIds, FlightStatus.IN_FLIGHT);
        }
    }

    private int activateFlights(LocalDateTime simulatedNow, LocalDateTime horizon) {
        List<TravelStop> pendingStops = travelStopRepository.findPendingStopsForActivation(simulatedNow, horizon);
        if (pendingStops.isEmpty()) {
            return 0;
        }
        int activatedCount = 0;

        java.util.Map<Long, Flight> flightsById = pendingStops.stream()
                .map(TravelStop::getFlight)
                .collect(java.util.stream.Collectors.toMap(Flight::getId, flight -> flight, (first, second) -> first, java.util.LinkedHashMap::new));
        java.util.Map<Long, List<TravelStop>> pendingStopsByFlightId = pendingStops.stream()
                .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getFlight().getId()));
        java.util.List<TravelStop> allStops = travelStopRepository.findByShipmentInOrderByShipmentIdAscStopOrderAsc(
                pendingStops.stream().map(TravelStop::getShipment).distinct().toList());
        java.util.Map<Long, TravelStop> originStopByShipmentId = new java.util.HashMap<>();
        java.util.Map<String, TravelStop> stopByShipmentAndOrder = new java.util.HashMap<>();
        for (TravelStop candidate : allStops) {
            Long shipmentId = candidate.getShipment() == null ? null : candidate.getShipment().getId();
            if (shipmentId == null) {
                continue;
            }
            if (candidate.getStopOrder() != null && candidate.getStopOrder() == 0) {
                originStopByShipmentId.put(shipmentId, candidate);
            }
            if (candidate.getStopOrder() != null) {
                stopByShipmentAndOrder.put(shipmentId + ":" + candidate.getStopOrder(), candidate);
            }
        }

        List<Flight> toStart = new java.util.ArrayList<>(flightsById.values());

        for (Flight flight : toStart) {
            flight.setStatus(FlightStatus.IN_FLIGHT);

            List<TravelStop> stops = pendingStopsByFlightId.getOrDefault(flight.getId(), List.of());

            for (TravelStop stop : stops) {
                Shipment shipment = stop.getShipment();
                Long shipmentId = shipment == null ? null : shipment.getId();
                if (shipmentId == null) {
                    continue;
                }
                TravelStop originStop = originStopByShipmentId.get(shipmentId);
                if (originStop != null && originStop.getStopStatus() == StopStatus.PENDING) {
                    originStop.setStopStatus(StopStatus.COMPLETED);
                    originStop.setActualArrival(flight.getScheduledDeparture() != null ? flight.getScheduledDeparture() : horizon);
                }

                stop.setStopStatus(StopStatus.IN_TRANSIT);
                activatedCount++;

                int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
                flight.setReservedLoad(Math.max(0, flight.getReservedLoad() - luggage));
                flight.setCurrentLoad(Math.min(flight.getMaxCapacity(), flight.getCurrentLoad() + luggage));

                // Issue 7.1: release storage at the intermediate hub the shipment is departing from.
                // stopOrder >= 2 means the previous stop was a transit airport that received this
                // luggage (storage was incremented on arrival in closeFlightsAndAdvanceStops).
                if (stop.getStopOrder() != null && stop.getStopOrder() >= 2) {
                    TravelStop previousHub = stopByShipmentAndOrder.get(shipmentId + ":" + (stop.getStopOrder() - 1));
                    if (previousHub != null && previousHub.getAirport() != null) {
                        Airport hub = previousHub.getAirport();
                        int newLoad = Math.max(0, hub.getCurrentStorageLoad() - shipment.getLuggageCount());
                        hub.setCurrentStorageLoad(newLoad);
                    }
                }

                shipment.setStatus(ShipmentStatus.IN_ROUTE);

                audit(shipment, ShipmentAuditType.DEPARTED,
                        "Vuelo " + flight.getFlightCode() + " en curso hacia " + stop.getAirport().getIcaoCode(),
                        stop.getAirport(), flight.getFlightCode());
            }
        }
        return activatedCount;
    }

    private int closeFlightsAndAdvanceStops(LocalDateTime simulatedNow, LocalDateTime horizon) {
        List<Flight> toComplete = flightRepository
                .findByStatusAndScheduledArrivalGreaterThanAndScheduledArrivalLessThanEqual(
                        FlightStatus.IN_FLIGHT,
                        simulatedNow,
                        horizon
                );
        int completedCount = 0;

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

            List<TravelStop> impacted = impactedByFlightId.getOrDefault(flight.getId(), List.of());

            for (TravelStop stop : impacted) {
                Shipment shipment = stop.getShipment();
                int luggage = shipment.getLuggageCount() == null ? 0 : shipment.getLuggageCount();
                flight.setCurrentLoad(Math.max(0, flight.getCurrentLoad() - luggage));
                stop.setStopStatus(StopStatus.COMPLETED);
                completedCount++;
                // Usar la llegada REAL del vuelo (no el fin del salto), clave a alta velocidad para
                // no marcar tarde entregas que en realidad llegaron a tiempo dentro del salto.
                LocalDateTime arrivalAt = flight.getScheduledArrival() != null ? flight.getScheduledArrival() : horizon;
                stop.setActualArrival(arrivalAt);

                Airport airport = stop.getAirport();
                airport.setCurrentStorageLoad(Math.min(
                        airport.getMaxStorageCapacity(),
                        airport.getCurrentStorageLoad() + shipment.getLuggageCount()
                ));

                audit(shipment, ShipmentAuditType.ARRIVED,
                        "Arribo a " + airport.getIcaoCode() + " mediante " + flight.getFlightCode(),
                        airport, flight.getFlightCode());

                List<TravelStop> allStops = allStopsByShipmentId.getOrDefault(shipment.getId(), List.of());
                boolean allDone = allStops.stream().allMatch(s -> s.getStopStatus() == StopStatus.COMPLETED);
                if (allDone) {
                    shipment.setStatus(ShipmentStatus.DELIVERED);
                    shipment.setDeliveredAt(arrivalAt);
                    shipment.setProgressPercentage(100.0);

                    // Issue 7.1: release destination storage — shipment handed to receiver,
                    // no longer occupying the airport's warehouse.
                    int destLoad = Math.max(0, airport.getCurrentStorageLoad() - shipment.getLuggageCount());
                    airport.setCurrentStorageLoad(destLoad);

                    audit(shipment, ShipmentAuditType.DELIVERED,
                            "Envio entregado en destino", airport, flight.getFlightCode());
                } else {
                    shipment.setStatus(ShipmentStatus.PENDING);
                    double progress = allStops.isEmpty()
                            ? 0.0
                            : (allStops.stream().filter(s -> s.getStopStatus() == StopStatus.COMPLETED).count() * 100.0) / allStops.size();
                    shipment.setProgressPercentage(progress);
                }
            }
        }
        return completedCount;
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

    /**
     * Colapso = el primer envío que no llega a tiempo. Consulta acotada a 1 fila (índice
     * idx_shipment_deadline), ejecutada solo en COLLAPSE_TEST y solo hasta detectarlo, por lo
     * que no implica escanear los envíos en cada tick. Registra el instante de colapso
     * (deadline del envío) y detiene la simulación.
     *
     * @return true si se detectó el colapso en este tick.
     */
    private boolean detectCollapse(SimulationConfig config, LocalDateTime horizon) {
        // El colapso solo cuenta envíos registrados DESDE la fecha de inicio del escenario. Sin esto, al
        // arrancar en (p.ej.) jul-2027 los millones de envíos previos —PENDING, nunca planificados en esta
        // corrida y con deadline ya vencido— se detectaban como "primer envío tarde" → colapso espurio día 1.
        LocalDateTime scenarioStart = config.getEffectiveScenarioStartAt() != null
                ? config.getEffectiveScenarioStartAt()
                : config.getScenarioStartAt();
        if (scenarioStart == null) {
            scenarioStart = LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        List<Shipment> late = shipmentRepository.findFirstLateShipment(
                scenarioStart, horizon, org.springframework.data.domain.PageRequest.of(0, 1));
        if (late.isEmpty()) {
            return false;
        }
        Shipment first = late.get(0);
        config.setCollapseDetectedAtSim(first.getDeadline());
        config.setCollapseShipmentId(first.getId());
        config.setCollapseShipmentCode(first.getShipmentCode());
        simulationConfigRepository.save(config);

        shipmentAuditService.log(
                first,
                ShipmentAuditType.DELAYED,
                "Colapso: primer envío fuera de plazo (deadline " + first.getDeadline() + ")",
                first.getDestinationAirport(),
                null
        );

        runtimeService.stopSimulationOnly();
        log.warn("COLAPSO detectado: envío {} no llegó a tiempo (deadline {} <= horizonte {})",
                first.getShipmentCode(), first.getDeadline(), horizon);
        return true;
    }

    private LocalDateTime resolveInitialSimulationTime(LocalDateTime fallbackNow) {
        // Inicio configurado; si no, el primer vuelo programado o el primer envío importado.
        SimulationConfig config = getConfig();
        if (config.getScenarioStartAt() != null) {
            return config.getScenarioStartAt();
        }
        LocalDateTime minDeparture = flightRepository.findMinScheduledDeparture();
        if (minDeparture != null) {
            return minDeparture.minusMinutes(5);
        }
        LocalDateTime min = shipmentRepository.findMinRegistrationDate();
        return min == null ? fallbackNow : min;
    }

    private LocalDateTime resolvePeriodEnd(SimulationConfig config) {
        // Fin del escenario unificado para todos los modos (ver SimulationRuntimeService.resolveScenarioEnd).
        return runtimeService.resolveScenarioEnd(config);
    }

}
