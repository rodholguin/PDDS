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

        LocalDateTime dayStart = date != null ? date.atStartOfDay()         : null;
        LocalDateTime dayEnd   = date != null ? date.plusDays(1).atStartOfDay() : null;

        List<Flight> flights = flightRepository.findByStatusAndDate(status, dayStart, dayEnd);
        return ResponseEntity.ok(flights);
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
}
