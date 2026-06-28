package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentSource;
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
    private static final Duration COLLAPSE_SCAN_STEP = Duration.ofDays(1);

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
    // Elegibilidad POR-CONFIG: cada runtime (LIVE id=1 / SIM id=2) marca su próximo tick a su propio ritmo.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> nextEligibleByConfigId = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, LocalDateTime> collapseCheckedThroughByConfigId = new java.util.concurrent.ConcurrentHashMap<>();
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
        try {
            if (runtimeService.isPaused() || runtimeService.isResetting()) {
                return;
            }
            // Procesa TODOS los runtimes en curso: la operación viva (DAY_TO_DAY) y, si está activa, una
            // simulación (PERIOD/COLLAPSE) — concurrentes. Cada uno con su propio reloj (su fila) y su
            // propio ritmo; el source-scoping de executeTick los mantiene sin pisarse.
            List<SimulationConfig> running = simulationConfigRepository.findByIsRunningTrue();
            long nowMs = System.currentTimeMillis();
            for (SimulationConfig config : running) {
                if (config.getId() == null) {
                    continue;
                }
                long tickIntervalMs = runtimeService.tickIntervalMs(config);
                long nextEligibleAt = nextEligibleByConfigId.getOrDefault(config.getId(), 0L);
                if (nextEligibleAt > nowMs) {
                    continue;
                }
                nextEligibleByConfigId.put(config.getId(), nowMs + tickIntervalMs);
                long startedAt = System.currentTimeMillis();
                transactionTemplate.executeWithoutResult(status -> executeTick(config));
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (elapsedMs > tickIntervalMs) {
                    log.warn("Tick tardó {} ms en escenario {} (objetivo={} ms)", elapsedMs, config.getScenario(), tickIntervalMs);
                }
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
        nextEligibleByConfigId.clear();
        collapseCheckedThroughByConfigId.clear();
    }

    public void executeHeadlessTick(SimulationConfig config) {
        transactionTemplate.executeWithoutResult(status -> executeTick(config));
    }

    void executeTick(SimulationConfig config) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime simulatedNow = runtimeService.currentSimulationTime(config).orElseGet(() -> resolveInitialSimulationTime(now, config));
        long secondsAdvance = runtimeService.simulationSecondsPerTick(config);
        // DAY_TO_DAY (operación EN VIVO): el horizonte = hora real AHORA → procesa hasta el presente,
        // cubriendo el tiempo transcurrido desde el último tick (reloj interno = hora cotidiana). Los
        // demás escenarios (COLLAPSE/PERIOD) avanzan por secondsAdvance como antes.
        LocalDateTime horizon = config.getScenario() == SimulationScenario.DAY_TO_DAY
                ? now
                : simulatedNow.plusSeconds(secondsAdvance);
        LocalDateTime periodEnd = resolvePeriodEnd(config);
        if (periodEnd != null && !simulatedNow.isBefore(periodEnd)) {
            runtimeService.setSimulationTime(config, periodEnd);
            runtimeService.stopSimulationOnly(config);
            log.info("Simulacion finalizada automaticamente en {}", periodEnd);
            return;
        }
        LocalDateTime effectiveHorizon = periodEnd != null && horizon.isAfter(periodEnd) ? periodEnd : horizon;
        LocalDateTime planningHorizon = effectiveHorizon;
        // Plan-ahead guard (PERIOD_SIMULATION y COLLAPSE_TEST): el reloj no adelanta más allá de lo
        // ya planificado (plannedThrough). Como los envíos inviables quedan CRITICAL (excluidos del
        // conteo de "sin ruta"), no hay deadlock y el colapso refleja envíos que de verdad no llegan.
        if (config.getScenario() == SimulationScenario.PERIOD_SIMULATION
                || config.getScenario() == SimulationScenario.COLLAPSE_TEST) {
            LocalDateTime plannedThrough = runtimeService.periodPlannedThrough().orElse(null);
            if (plannedThrough == null) {
                LocalDateTime waitFrontier = config.getEffectiveScenarioStartAt() != null
                        ? config.getEffectiveScenarioStartAt()
                        : config.getScenarioStartAt();
                runtimeService.recordPeriodTickWait(effectiveHorizon, waitFrontier);
                log.info("Tick plan-ahead en espera: escenario={} horizon={} plannedThrough=null", config.getScenario(), effectiveHorizon);
                return;
            }
            if (plannedThrough != null
                    && plannedThrough.isBefore(effectiveHorizon)
                    && (periodEnd == null || effectiveHorizon.isBefore(periodEnd))) {
                if (config.getScenario() == SimulationScenario.COLLAPSE_TEST) {
                    if (config.getCollapseDetectedAtSim() == null
                            && detectCollapse(config, effectiveHorizon, plannedThrough)) {
                        return;
                    }
                }

                if (!plannedThrough.isAfter(simulatedNow)) {
                    if (simulatedNow.isAfter(plannedThrough)) {
                        runtimeService.setSimulationTime(config, plannedThrough);
                    }
                    runtimeService.recordPeriodTickWait(effectiveHorizon, plannedThrough);
                    log.info("Tick plan-ahead en espera: escenario={} horizon={} plannedThrough={}", config.getScenario(), effectiveHorizon, plannedThrough);
                    return;
                }

                effectiveHorizon = plannedThrough;
                planningHorizon = plannedThrough;
            }
        }

        long tickStartedAt = System.nanoTime();
        boolean planAheadScenario = config.getScenario() == SimulationScenario.PERIOD_SIMULATION
                || config.getScenario() == SimulationScenario.COLLAPSE_TEST;
        long afterReconcile;
        long afterActivate;
        if (planAheadScenario) {
            afterReconcile = System.nanoTime();
            afterActivate = afterReconcile;
        } else {
            reconcileFlightStates(simulatedNow, effectiveHorizon);
            afterReconcile = System.nanoTime();
            ShipmentSource scope = ShipmentSource.LIVE;
            for (int legPass = 0; legPass < 50; legPass++) {
                int activated = activateFlights(simulatedNow, effectiveHorizon, scope);
                int completed = closeFlightsAndAdvanceStops(simulatedNow, effectiveHorizon, scope);
                if (activated + completed == 0) {
                    break;
                }
            }
            afterActivate = System.nanoTime();
        }
        long afterClose = afterActivate;

        runtimeService.setSimulationTime(config, effectiveHorizon);
        long afterSetTime = System.nanoTime();
        runtimeService.markTick(config, effectiveHorizon);
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
                && detectCollapse(config, effectiveHorizon, runtimeService.periodPlannedThrough().orElse(effectiveHorizon))) {
            return;
        }

        if (periodEnd != null && !effectiveHorizon.isBefore(periodEnd)) {
            runtimeService.stopSimulationOnly(config);
            log.info("Simulacion de periodo finalizada automaticamente en {}", effectiveHorizon);
        }
    }

    private void reconcileFlightStates(LocalDateTime simulatedNow, LocalDateTime horizon) {
        // Incluye vuelos que despegan Y aterrizan dentro del mismo salto de reloj (arrival > simulatedNow),
        // no solo los que siguen volando al final; así a alta velocidad ningún vuelo corto queda sin
        // procesar (se activa + completa en el mismo tick) y no se generan colapsos falsos.
        // Cota inferior = reloj - 24h: ningún vuelo en aire (duración máx 21h) despegó antes de eso, así
        // que no se pierde ninguno; pero el escaneo deja de crecer con la corrida → tick ~1.5s → decenas de ms.
        List<Long> recoverableScheduledIds = flightRepository.findRecoverableScheduledFlightIds(simulatedNow.minusHours(24), simulatedNow, horizon);
        if (!recoverableScheduledIds.isEmpty()) {
            flightRepository.updateStatusByIds(recoverableScheduledIds, FlightStatus.IN_FLIGHT);
        }
    }

    private int activateFlights(LocalDateTime simulatedNow, LocalDateTime horizon, ShipmentSource scope) {
        List<TravelStop> pendingStops = travelStopRepository.findPendingStopsForActivation(simulatedNow, horizon).stream()
                .filter(stop -> stop.getShipment() != null && stop.getShipment().getSource() == scope)
                .toList();
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

    private int closeFlightsAndAdvanceStops(LocalDateTime simulatedNow, LocalDateTime horizon, ShipmentSource scope) {
        List<Flight> toComplete = flightRepository
                .findByStatusAndScheduledArrivalGreaterThanAndScheduledArrivalLessThanEqual(
                        FlightStatus.IN_FLIGHT,
                        simulatedNow,
                        horizon
                );
        int completedCount = 0;

        List<TravelStop> impactedStops = toComplete.isEmpty()
                ? List.of()
                : travelStopRepository.findByFlightInAndStopStatus(toComplete, StopStatus.IN_TRANSIT).stream()
                        .filter(stop -> stop.getShipment() != null && stop.getShipment().getSource() == scope)
                        .toList();
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
                int maxStopOrder = allStops.stream()
                        .map(TravelStop::getStopOrder)
                        .filter(java.util.Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(-1);
                boolean isFinalDestinationStop = stop.getStopOrder() != null
                        && stop.getStopOrder() == maxStopOrder
                        && shipment.getDestinationAirport() != null
                        && stop.getAirport() != null
                        && java.util.Objects.equals(stop.getAirport().getId(), shipment.getDestinationAirport().getId());
                if (allDone && isFinalDestinationStop) {
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
        SimulationConfig config = simulationConfigRepository.findLiveConfigOrFirst();
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
    private boolean detectCollapse(SimulationConfig config, LocalDateTime horizon, LocalDateTime planningCoveredThrough) {
        // El colapso solo cuenta envíos registrados DESDE la fecha de inicio del escenario. Sin esto, al
        // arrancar en (p.ej.) jul-2027 los millones de envíos previos —PENDING, nunca planificados en esta
        // corrida y con deadline ya vencido— se detectaban como "primer envío tarde" → colapso espurio día 1.
        LocalDateTime scenarioStart = config.getEffectiveScenarioStartAt() != null
                ? config.getEffectiveScenarioStartAt()
                : config.getScenarioStartAt();
        if (scenarioStart == null) {
            scenarioStart = LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        LocalDateTime coveredThrough = planningCoveredThrough == null ? horizon : planningCoveredThrough;
        Long configId = config.getId();
        if (configId != null) {
            LocalDateTime checkedThrough = collapseCheckedThroughByConfigId.get(configId);
            if (checkedThrough != null && !horizon.isAfter(checkedThrough.plus(COLLAPSE_SCAN_STEP))) {
                return false;
            }
        }
        LocalDateTime queryCoveredThrough = coveredThrough.isAfter(horizon) ? horizon : coveredThrough;
        List<Shipment> late = shipmentRepository.findFirstLateShipment(
                scenarioStart,
                horizon,
                queryCoveredThrough,
                org.springframework.data.domain.PageRequest.of(0, 1));
        if (late.isEmpty()) {
            if (configId != null) {
                collapseCheckedThroughByConfigId.put(configId, horizon);
            }
            return false;
        }
        Shipment first = late.get(0);
        LocalDateTime effectiveDeadline = effectiveDeadline(first);
        config.setCollapseDetectedAtSim(effectiveDeadline);
        config.setCollapseShipmentId(first.getId());
        config.setCollapseShipmentCode(first.getShipmentCode());
        simulationConfigRepository.save(config);

        first.setStatus(ShipmentStatus.CRITICAL);
        first.setDeadline(effectiveDeadline);
        shipmentRepository.save(first);
        runtimeService.setSimulationTime(config, effectiveDeadline);

        shipmentAuditService.log(
                first,
                ShipmentAuditType.DELAYED,
                "Colapso: primer envío fuera de plazo (deadline " + effectiveDeadline + ")",
                first.getDestinationAirport(),
                null
        );

        runtimeService.stopSimulationOnly(config);
        log.warn("COLAPSO detectado: envío {} no llegó a tiempo (deadline {} <= horizonte {})",
                first.getShipmentCode(), effectiveDeadline, horizon);
        return true;
    }

    private LocalDateTime effectiveDeadline(Shipment shipment) {
        if (shipment == null || shipment.getRegistrationDate() == null) {
            return shipment == null ? null : shipment.getDeadline();
        }
        return Boolean.TRUE.equals(shipment.getIsInterContinental())
                ? shipment.getRegistrationDate().plusDays(2)
                : shipment.getRegistrationDate().plusDays(1);
    }

    private LocalDateTime resolveInitialSimulationTime(LocalDateTime fallbackNow, SimulationConfig config) {
        // DAY_TO_DAY es la operación EN VIVO: su reloj interno = hora real actual, no una fecha histórica.
        if (config != null && config.getScenario() == SimulationScenario.DAY_TO_DAY) {
            return fallbackNow;
        }
        // Otros escenarios: inicio configurado; si no, el primer vuelo programado o el primer envío importado.
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
