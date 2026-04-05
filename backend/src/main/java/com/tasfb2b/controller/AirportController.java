package com.tasfb2b.controller;

import com.tasfb2b.dto.AirportResponseDto;
import com.tasfb2b.model.*;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/airports")
@RequiredArgsConstructor
@Tag(name = "Aeropuertos", description = "Consulta de aeropuertos y niveles de ocupación")
public class AirportController {

    private final AirportRepository          airportRepository;
    private final FlightRepository           flightRepository;
    private final SimulationConfigRepository configRepository;

    // ── Listados ──────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Todos los aeropuertos con porcentaje de ocupación y semáforo")
    public ResponseEntity<List<AirportResponseDto>> getAll() {
        int[] thresholds = getThresholds();
        List<AirportResponseDto> dtos = airportRepository.findAll().stream()
                .map(a -> AirportResponseDto.from(a, thresholds[0], thresholds[1]))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/bottlenecks")
    @Operation(summary = "Aeropuertos en estado ALERTA o CRITICO (cuellos de botella)")
    public ResponseEntity<List<AirportResponseDto>> getBottlenecks() {
        int[] thresholds = getThresholds();
        int normalPct  = thresholds[0];
        int warningPct = thresholds[1];

        List<AirportResponseDto> bottlenecks = airportRepository.findAll().stream()
                .filter(a -> {
                    AirportStatus s = a.getStatus(normalPct, warningPct);
                    return s == AirportStatus.ALERTA || s == AirportStatus.CRITICO;
                })
                .sorted((a, b) -> Double.compare(b.getOccupancyPct(), a.getOccupancyPct()))
                .map(a -> AirportResponseDto.from(a, normalPct, warningPct))
                .toList();

        return ResponseEntity.ok(bottlenecks);
    }

    // ── Detalle ───────────────────────────────────────────────────────────────

    @GetMapping("/{icao}")
    @Operation(summary = "Detalle de un aeropuerto con sus vuelos activos")
    public ResponseEntity<Map<String, Object>> getByIcao(@PathVariable String icao) {
        Airport airport = airportRepository.findByIcaoCode(icao.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aeropuerto no encontrado: " + icao));

        int[] t = getThresholds();
        AirportResponseDto dto = AirportResponseDto.from(airport, t[0], t[1]);

        List<Flight> activeFlights = flightRepository.findByAirport(airport).stream()
                .filter(f -> f.getStatus() == FlightStatus.SCHEDULED
                          || f.getStatus() == FlightStatus.IN_FLIGHT)
                .toList();

        // Proyección ligera de los vuelos para no exponer toda la entidad
        List<Map<String, Object>> flightSummaries = activeFlights.stream()
                .map(f -> Map.<String, Object>ofEntries(
                        Map.entry("id", f.getId()),
                        Map.entry("flightCode", f.getFlightCode()),
                        Map.entry("originIcao", f.getOriginAirport().getIcaoCode()),
                        Map.entry("destinationIcao", f.getDestinationAirport().getIcaoCode()),
                        Map.entry("isInterContinental", f.getIsInterContinental()),
                        Map.entry("maxCapacity", f.getMaxCapacity()),
                        Map.entry("currentLoad", f.getCurrentLoad()),
                        Map.entry("availableCapacity", f.getAvailableCapacity()),
                        Map.entry("loadPct", f.getLoadPct()),
                        Map.entry("scheduledDeparture", f.getScheduledDeparture()),
                        Map.entry("status", f.getStatus())
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "airport",      dto,
                "activeFlights", flightSummaries,
                "intraAvailableCapacity", flightSummaries.stream()
                        .filter(f -> Boolean.FALSE.equals(f.get("isInterContinental")))
                        .mapToInt(f -> ((Number) f.getOrDefault("availableCapacity", 0)).intValue())
                        .sum(),
                "interAvailableCapacity", flightSummaries.stream()
                        .filter(f -> Boolean.TRUE.equals(f.get("isInterContinental")))
                        .mapToInt(f -> ((Number) f.getOrDefault("availableCapacity", 0)).intValue())
                        .sum()
        ));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Retorna [normalThresholdPct, warningThresholdPct] desde SimulationConfig. */
    private int[] getThresholds() {
        return configRepository.findAll().stream()
                .findFirst()
                .map(c -> new int[]{c.getNormalThresholdPct(), c.getWarningThresholdPct()})
                .orElse(new int[]{70, 90});
    }
}
