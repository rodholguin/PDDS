package com.tasfb2b.controller;

import com.tasfb2b.model.*;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.RoutePlannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Vuelos", description = "Consulta y gestión de vuelos")
public class FlightController {

    private final FlightRepository     flightRepository;
    private final TravelStopRepository travelStopRepository;
    private final ShipmentRepository   shipmentRepository;
    private final RoutePlannerService  routePlannerService;

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
            flights = flightRepository.findByStatusAndDate(status, dayStart, dayEnd);
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
        List<Flight> base;
        if (date == null) {
            base = status == null ? flightRepository.findAll() : flightRepository.findByStatus(status);
        } else {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            base = flightRepository.findByStatusAndDate(status, dayStart, dayEnd);
        }

        String codeFilter = normalizeContains(code);
        String originFilter = normalizeExact(origin);
        String destinationFilter = normalizeExact(destination);

        Comparator<Flight> comparator = switch (sort) {
            case "flightCode" -> Comparator.comparing(Flight::getFlightCode, String.CASE_INSENSITIVE_ORDER);
            case "scheduledArrival" -> Comparator.comparing(Flight::getScheduledArrival, Comparator.nullsLast(LocalDateTime::compareTo));
            default -> Comparator.comparing(Flight::getScheduledDeparture, Comparator.nullsLast(LocalDateTime::compareTo));
        };
        if ("desc".equalsIgnoreCase(direction)) {
            comparator = comparator.reversed();
        }

        List<Flight> filtered = base.stream()
                .filter(f -> codeFilter == null || f.getFlightCode().toLowerCase().contains(codeFilter))
                .filter(f -> originFilter == null || originFilter.equalsIgnoreCase(f.getOriginAirport().getIcaoCode()))
                .filter(f -> destinationFilter == null || destinationFilter.equalsIgnoreCase(f.getDestinationAirport().getIcaoCode()))
                .sorted(comparator)
                .toList();

        long totalElements = filtered.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) safeSize);
        int fromIndex = Math.min(filtered.size(), safePage * safeSize);
        int toIndex = Math.min(filtered.size(), fromIndex + safeSize);
        List<Flight> pageContent = filtered.subList(fromIndex, toIndex);

        return ResponseEntity.ok(Map.of(
                "content", pageContent,
                "page", safePage,
                "size", safeSize,
                "totalElements", totalElements,
                "totalPages", totalPages,
                "hasNext", safePage + 1 < totalPages,
                "hasPrevious", safePage > 0
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
        List<Map<String, Object>> assignedShipments = travelStopRepository.findAll().stream()
                .filter(ts -> ts.getFlight() != null
                           && ts.getFlight().getId().equals(id))
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

        return ResponseEntity.ok(Map.of(
                "flight",           flight,
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
        return trimmed.isBlank() ? null : trimmed.toLowerCase();
    }

    private String normalizeExact(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed.toUpperCase();
    }
}
