package com.tasfb2b.controller;

import com.tasfb2b.model.*;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.FlightScheduleService;
import com.tasfb2b.service.OperationalAlertService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.SimulationRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/flights")
@Slf4j
@Tag(name = "Vuelos", description = "Consulta y gestión de vuelos")
public class FlightController {

    private final FlightRepository     flightRepository;
    private final TravelStopRepository travelStopRepository;
    private final ShipmentRepository   shipmentRepository;
    private final RoutePlannerService  routePlannerService;
    private final FlightScheduleService flightScheduleService;
    private final SimulationRuntimeService simulationRuntimeService;
    private final OperationalAlertService operationalAlertService;

    public FlightController(
            FlightRepository flightRepository,
            TravelStopRepository travelStopRepository,
            ShipmentRepository shipmentRepository,
            RoutePlannerService routePlannerService,
            FlightScheduleService flightScheduleService,
            SimulationRuntimeService simulationRuntimeService,
            OperationalAlertService operationalAlertService
    ) {
        this.flightRepository = flightRepository;
        this.travelStopRepository = travelStopRepository;
        this.shipmentRepository = shipmentRepository;
        this.routePlannerService = routePlannerService;
        this.flightScheduleService = flightScheduleService;
        this.simulationRuntimeService = simulationRuntimeService;
        this.operationalAlertService = operationalAlertService;
    }

    // ── Listados ──────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Listar vuelos con filtro opcional por estado y/o fecha de salida")
    public ResponseEntity<List<Flight>> list(
            @RequestParam(required = false) FlightStatus status,
            @RequestParam(required = false) LocalDate date) {
        List<Flight> flights;
        if (date == null) {
            flights = status == null ? flightRepository.findAll() : flightRepository.findByStatus(status);
        } else {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            flights = (status == null ? flightRepository.findAll() : flightRepository.findByStatus(status)).stream()
                    .filter(f -> f.getScheduledDeparture() != null)
                    .filter(f -> !f.getScheduledDeparture().isBefore(dayStart))
                    .filter(f -> f.getScheduledDeparture().isBefore(dayEnd))
                    .toList();
        }
        return ResponseEntity.ok(flights);
    }

    @GetMapping("/search")
    @Operation(summary = "Listar vuelos paginados con filtros")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) FlightStatus status,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "scheduledDeparture") String sort,
            @RequestParam(defaultValue = "asc") String direction
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(200, size));
        LocalDateTime dayStart = null;
        LocalDateTime dayEnd = null;
        if (date != null) {
            dayStart = date.atStartOfDay();
            dayEnd = date.plusDays(1).atStartOfDay();
        }

        Sort sortSpec = switch (sort) {
            case "flightCode" -> Sort.by(Sort.Direction.fromString(direction), "flightCode");
            case "scheduledArrival" -> Sort.by(Sort.Direction.fromString(direction), "scheduledArrival");
            default -> Sort.by(Sort.Direction.fromString(direction), "scheduledDeparture");
        };

        Pageable pageable = PageRequest.of(safePage, safeSize, sortSpec);
        String normalizedCode = normalizeContains(code);
        String normalizedOrigin = normalizeExact(origin);
        String normalizedDestination = normalizeExact(destination);
        LocalDateTime filterDayStart = dayStart;
        LocalDateTime filterDayEnd = dayEnd;

        Specification<Flight> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (normalizedCode != null) {
                predicates.add(cb.like(cb.lower(root.get("flightCode")), normalizedCode));
            }
            if (normalizedOrigin != null) {
                predicates.add(cb.equal(root.get("originAirport").get("icaoCode"), normalizedOrigin));
            }
            if (normalizedDestination != null) {
                predicates.add(cb.equal(root.get("destinationAirport").get("icaoCode"), normalizedDestination));
            }
            if (filterDayStart != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledDeparture"), filterDayStart));
            }
            if (filterDayEnd != null) {
                predicates.add(cb.lessThan(root.get("scheduledDeparture"), filterDayEnd));
            }
            if (status == FlightStatus.IN_FLIGHT) {
                LocalDateTime now = simulationRuntimeService.effectiveNow();
                predicates.add(cb.lessThanOrEqualTo(root.get("scheduledDeparture"), now));
                predicates.add(cb.greaterThan(root.get("scheduledArrival"), now));
                predicates.add(cb.greaterThan(root.get("currentLoad"), 0));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<Flight> result = flightRepository.findAll(spec, pageable);

        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "hasNext", result.hasNext(),
                "hasPrevious", result.hasPrevious()
        ));
    }

    @GetMapping("/capacity-view")
    @Operation(summary = "Vista de capacidad de vuelos para asignación")
    public ResponseEntity<List<Map<String, Object>>> capacityView(@RequestParam(required = false) LocalDate date) {
        List<Flight> flights;
        if (date == null) {
            flights = flightRepository.findByStatus(FlightStatus.SCHEDULED);
        } else {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            flights = flightRepository.findByStatusAndDate(FlightStatus.SCHEDULED, dayStart, dayEnd);
        }
        List<Map<String, Object>> response = flights.stream()
                .map(flight -> Map.<String, Object>of(
                        "flightCode", flight.getFlightCode(),
                        "originIcao", flight.getOriginAirport().getIcaoCode(),
                        "destinationIcao", flight.getDestinationAirport().getIcaoCode(),
                        "routeType", Boolean.TRUE.equals(flight.getIsInterContinental()) ? "INTER" : "INTRA",
                        "maxCapacity", flight.getMaxCapacity(),
                        "currentLoad", flight.getCurrentLoad(),
                        "availableCapacity", flight.getAvailableCapacity(),
                        "scheduledDeparture", flight.getScheduledDeparture()
                ))
                .toList();
        return ResponseEntity.ok(response);
    }

    // ── Detalle ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de un vuelo con los envíos (maletas) asignados")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado: " + id));

        // Envíos cuya ruta incluye este vuelo (a través de TravelStop)
        List<Map<String, Object>> assignedShipments = travelStopRepository.findByFlight(flight).stream()
                .filter(ts -> ts.getShipment() != null)
                .map(ts -> {
                    Shipment s = ts.getShipment();
                    return Map.<String, Object>of(
                            "shipmentId",   s.getId(),
                            "shipmentCode", s.getShipmentCode(),
                            "airlineName",  s.getAirlineName(),
                            "luggageCount", s.getLuggageCount(),
                            "status",       s.getStatus(),
                            "stopOrder",    ts.getStopOrder()
                    );
                })
                .toList();

        Map<String, Object> flightDto = Map.ofEntries(
                Map.entry("id", flight.getId()),
                Map.entry("flightCode", flight.getFlightCode()),
                Map.entry("originIcao", flight.getOriginAirport().getIcaoCode()),
                Map.entry("destinationIcao", flight.getDestinationAirport().getIcaoCode()),
                Map.entry("status", flight.getStatus()),
                Map.entry("scheduledDeparture", flight.getScheduledDeparture()),
                Map.entry("scheduledArrival", flight.getScheduledArrival()),
                Map.entry("maxCapacity", flight.getMaxCapacity()),
                Map.entry("currentLoad", flight.getCurrentLoad()),
                Map.entry("availableCapacity", flight.getAvailableCapacity()),
                Map.entry("loadPct", flight.getLoadPct()),
                Map.entry("routeType", Boolean.TRUE.equals(flight.getIsInterContinental()) ? "INTER" : "INTRA")
        );

        return ResponseEntity.ok(Map.of(
                "flight",           flightDto,
                "assignedShipments", assignedShipments,
                "loadPct",          flight.getLoadPct(),
                "availableCapacity", flight.getAvailableCapacity()
        ));
    }

    // ── Cancelación ───────────────────────────────────────────────────────────

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancelar un vuelo y disparar replanificación de todos sus envíos",
               description = "Marca el vuelo como CANCELLED, luego replanifica todos los " +
                             "envíos cuya ruta pase por ese vuelo.")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado: " + id));

        if (flight.getStatus() == FlightStatus.COMPLETED) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No se puede cancelar un vuelo ya completado"));
        }

        // 1. Cancelar el vuelo
        flight.setStatus(FlightStatus.CANCELLED);
        flightRepository.save(flight);
        log.warn("[FlightController] Vuelo {} cancelado. Buscando envíos afectados…",
                flight.getFlightCode());

        // 2. Encontrar todos los TravelStop que usaban este vuelo
        List<TravelStop> affectedStops = travelStopRepository.findAll().stream()
                .filter(ts -> ts.getFlight() != null
                           && ts.getFlight().getId().equals(id)
                           && ts.getStopStatus() == StopStatus.PENDING)
                .toList();

        // 3. Replanificar cada envío afectado
        int replanned = 0;
        int failed    = 0;
        for (TravelStop stop : affectedStops) {
            try {
                routePlannerService.replanShipment(stop.getShipment().getId());
                operationalAlertService.ensureShipmentAlert(stop.getShipment(), "FLIGHT_CANCELLED", "Vuelo cancelado: " + flight.getFlightCode());
                replanned++;
            } catch (Exception e) {
                failed++;
                log.error("[FlightController] Error replanificando envío {}: {}",
                        stop.getShipment().getShipmentCode(), e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "flightCode",       flight.getFlightCode(),
                "status",           "CANCELLED",
                "affectedShipments", affectedStops.size(),
                "replanned",        replanned,
                "failedToReplan",   failed
        ));
    }

    private String normalizeContains(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : "%" + trimmed.toLowerCase() + "%";
    }

    private String normalizeExact(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed.toUpperCase();
    }
}
