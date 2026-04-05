package com.tasfb2b.service;

import com.tasfb2b.dto.SimulationEventDto;
import com.tasfb2b.dto.SimulationKpisDto;
import com.tasfb2b.dto.SimulationStateDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.OperationalAlertRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado de ejecución de simulación en memoria para soporte UI.
 */
@Service
@RequiredArgsConstructor
public class SimulationRuntimeService {

    private static final String KEY_PAUSED = "paused";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_REPLANS = "replans";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_LAST_TICK = "lastTick";

    private final SimulationConfigRepository configRepository;
    private final ShipmentRepository shipmentRepository;
    private final FlightRepository flightRepository;
    private final AirportRepository airportRepository;
    private final TravelStopRepository travelStopRepository;
    private final ShipmentAuditLogRepository shipmentAuditLogRepository;
    private final OperationalAlertRepository operationalAlertRepository;
    private final ShipmentAuditService shipmentAuditService;

    private final Map<String, Object> runtime = new ConcurrentHashMap<>();
    private final AtomicInteger defaultSpeed = new AtomicInteger(1);

    @Transactional(readOnly = true)
    public SimulationStateDto getState() {
        SimulationConfig config = getConfig();
        return new SimulationStateDto(
                config.getId(),
                config.getScenario(),
                valueOr(config.getSimulationDays(), 5),
                valueOr(config.getExecutionMinutes(), 60),
                valueOr(config.getInitialVolumeAvg(), 8),
                valueOr(config.getInitialVolumeVariance(), 3),
                valueOr(config.getFlightFrequencyMultiplier(), 1),
                valueOr(config.getCancellationRatePct(), 5),
                valueOr(config.getIntraNodeCapacity(), 700),
                valueOr(config.getInterNodeCapacity(), 800),
                valueOr(config.getNormalThresholdPct(), 70),
                valueOr(config.getWarningThresholdPct(), 90),
                config.getPrimaryAlgorithm(),
                config.getSecondaryAlgorithm(),
                Boolean.TRUE.equals(config.getIsRunning()),
                isPaused(),
                currentSpeed(),
                replannings(),
                injectedEvents(),
                config.getStartedAt(),
                (LocalDateTime) runtime.get(KEY_LAST_TICK),
                LocalDateTime.now()
        );
    }

    public void markStarted() {
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.putIfAbsent(KEY_SPEED, defaultSpeed.get());
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    public void markPaused() {
        runtime.put(KEY_PAUSED, Boolean.TRUE);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    public void markStopped() {
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    public void clearPausedFlag() {
        runtime.put(KEY_PAUSED, Boolean.FALSE);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    @Transactional
    public void resetSimulation() {
        resetDemandKeepingNetwork();
    }

    @Transactional
    public void resetDemandKeepingNetwork() {
        resetRuntimeState();

        var flights = flightRepository.findAll();
        for (Flight flight : flights) {
            flight.setStatus(FlightStatus.SCHEDULED);
            flight.setCurrentLoad(0);
        }
        flightRepository.saveAll(flights);

        var airports = airportRepository.findAll();
        for (Airport airport : airports) {
            airport.setCurrentStorageLoad(0);
        }
        airportRepository.saveAll(airports);

        travelStopRepository.deleteAllInBatch();
        shipmentAuditLogRepository.deleteAllInBatch();
        operationalAlertRepository.deleteAllInBatch();
        shipmentRepository.deleteAllInBatch();
    }

    private void resetRuntimeState() {
        SimulationConfig config = getConfig();
        config.setIsRunning(false);
        config.setStartedAt(null);
        configRepository.save(config);
        markStopped();
        runtime.put(KEY_SPEED, defaultSpeed.get());
        runtime.put(KEY_REPLANS, 0L);
        runtime.put(KEY_EVENTS, 0L);
    }

    public void markTick(LocalDateTime tickAt) {
        runtime.put(KEY_LAST_TICK, tickAt == null ? LocalDateTime.now() : tickAt);
    }

    public void setSpeed(int speed) {
        runtime.put(KEY_SPEED, speed);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    public int currentSpeed() {
        return (int) runtime.getOrDefault(KEY_SPEED, defaultSpeed.get());
    }

    public boolean isPaused() {
        return Boolean.TRUE.equals(runtime.getOrDefault(KEY_PAUSED, Boolean.FALSE));
    }

    public long replannings() {
        return ((Number) runtime.getOrDefault(KEY_REPLANS, 0L)).longValue();
    }

    public long injectedEvents() {
        return ((Number) runtime.getOrDefault(KEY_EVENTS, 0L)).longValue();
    }

    public void increaseReplanningCounter() {
        long cur = replannings();
        runtime.put(KEY_REPLANS, cur + 1);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());
    }

    @Transactional
    public String injectEvent(SimulationEventDto dto) {
        String type = dto.type().trim().toUpperCase();
        runtime.put(KEY_EVENTS, injectedEvents() + 1);
        runtime.put(KEY_LAST_TICK, LocalDateTime.now());

        return switch (type) {
            case "CANCEL_FLIGHT" -> cancelFlight(dto.flightId());
            case "INCREASE_VOLUME" -> increaseVolume(dto.eventValue());
            case "FLAG_SHIPMENT_CRITICAL" -> markShipmentCritical(dto.shipmentId(), dto.note());
            default -> "Evento registrado sin accion automatica: " + type;
        };
    }

    @Transactional(readOnly = true)
    public SimulationKpisDto computeKpis() {
        long delivered = shipmentRepository.countDeliveredTotal();
        long deliveredOnTime = shipmentRepository.countDeliveredOnTimeTotal();
        long active = shipmentRepository.findActiveShipments().size();
        long critical = shipmentRepository.findCriticalShipments().size();
        long delayed = shipmentRepository.findByStatus(ShipmentStatus.DELAYED).size();

        double deliveredPct = delivered == 0 ? 0.0 : (deliveredOnTime * 100.0) / delivered;
        double avgFlightLoad = flightRepository.findAll().stream()
                .mapToDouble(Flight::getLoadPct)
                .average()
                .orElse(0.0);
        double avgNodeLoad = airportRepository.findAll().stream()
                .mapToDouble(a -> a.getOccupancyPct())
                .average()
                .orElse(0.0);

        return new SimulationKpisDto(
                deliveredPct,
                avgFlightLoad,
                avgNodeLoad,
                replannings(),
                delivered,
                delayed,
                active,
                critical,
                injectedEvents()
        );
    }

    @Transactional
    public void markShipmentDelivered(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(LocalDateTime.now());
        shipment.setProgressPercentage(100.0);
        shipmentRepository.save(shipment);
    }

    private String cancelFlight(Long flightId) {
        if (flightId == null) {
            throw new IllegalArgumentException("flightId es obligatorio para CANCEL_FLIGHT");
        }

        Flight flight = flightRepository.findById(flightId)
                .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado: " + flightId));
        flight.setStatus(FlightStatus.CANCELLED);
        flightRepository.save(flight);

        shipmentRepository.findActiveShipments().stream()
                .filter(shipment -> shipment.getShipmentCode() != null)
                .forEach(shipment -> shipmentAuditService.log(
                        shipment,
                        ShipmentAuditType.EVENT_INJECTED,
                        "Evento manual: vuelo " + flight.getFlightCode() + " cancelado",
                        flight.getOriginAirport(),
                        flight.getFlightCode()
                ));

        increaseReplanningCounter();
        return "Vuelo " + flight.getFlightCode() + " cancelado e incluido en replanificacion";
    }

    private String increaseVolume(Integer eventValue) {
        int value = eventValue == null ? 10 : eventValue;
        return "Incremento de volumen inyectado en +" + value + " unidades";
    }

    private String markShipmentCritical(Long shipmentId, String note) {
        if (shipmentId == null) {
            throw new IllegalArgumentException("shipmentId es obligatorio para FLAG_SHIPMENT_CRITICAL");
        }

        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));
        shipment.setStatus(ShipmentStatus.CRITICAL);
        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.CRITICAL,
                "Evento manual: envio marcado como critico"
                        + (note == null || note.isBlank() ? "" : " - " + note.trim()),
                shipment.getOriginAirport(),
                null
        );

        return "Envío " + shipment.getShipmentCode() + " marcado en CRITICAL"
                + (note == null || note.isBlank() ? "" : " - " + note.trim());
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : configRepository.save(SimulationConfig.builder().build());
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
