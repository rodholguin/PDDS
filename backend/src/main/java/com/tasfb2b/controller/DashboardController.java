package com.tasfb2b.controller;

import com.tasfb2b.dto.DashboardKpisDto;
import com.tasfb2b.dto.SystemStatusDto;
import com.tasfb2b.model.*;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.RoutePlannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "KPIs y estado global del sistema para el mapa")
public class DashboardController {

    private final AirportRepository          airportRepository;
    private final FlightRepository           flightRepository;
    private final ShipmentRepository         shipmentRepository;
    private final SimulationConfigRepository configRepository;
    private final RoutePlannerService        routePlannerService;
    private final CollapseMonitorService     collapseMonitorService;

    @GetMapping("/kpis")
    @Operation(summary = "Los 4 KPIs principales para el mapa del dashboard",
               description = "Total de envíos, activos, críticos, entregados, carga del sistema y riesgo.")
    public ResponseEntity<DashboardKpisDto> getKpis() {
        int[] thresholds = getThresholds();

        long totalShipments    = shipmentRepository.count();
        long activeShipments   = shipmentRepository.findActiveShipments().size();
        long criticalShipments = shipmentRepository.findCriticalShipments().size();
        long delivered         = shipmentRepository.findByStatus(ShipmentStatus.DELIVERED).size();

        double sysLoad     = collapseMonitorService.computeSystemLoad();
        double collapseRisk = routePlannerService.getCollapseRisk();

        List<Airport> airports = airportRepository.findAll();
        int totalAirports = airports.size();

        long alerta  = airports.stream()
                .filter(a -> a.getStatus(thresholds[0], thresholds[1]) == AirportStatus.ALERTA)
                .count();
        long critico = airports.stream()
                .filter(a -> a.getStatus(thresholds[0], thresholds[1]) == AirportStatus.CRITICO)
                .count();

        return ResponseEntity.ok(new DashboardKpisDto(
                totalShipments,
                activeShipments,
                criticalShipments,
                delivered,
                sysLoad,
                collapseRisk,
                totalAirports,
                alerta,
                critico
        ));
    }

    @GetMapping("/system-status")
    @Operation(summary = "Distribución de aeropuertos y vuelos por estado",
               description = "Conteos para las barras de estado NORMAL / ALERTA / CRITICO.")
    public ResponseEntity<SystemStatusDto> getSystemStatus() {
        int[] thresholds = getThresholds();

        List<Airport> airports = airportRepository.findAll();
        long total   = airports.size();
        long normal  = airports.stream()
                .filter(a -> a.getStatus(thresholds[0], thresholds[1]) == AirportStatus.NORMAL)
                .count();
        long alerta  = airports.stream()
                .filter(a -> a.getStatus(thresholds[0], thresholds[1]) == AirportStatus.ALERTA)
                .count();
        long critico = airports.stream()
                .filter(a -> a.getStatus(thresholds[0], thresholds[1]) == AirportStatus.CRITICO)
                .count();

        double avgOccupancy = airports.stream()
                .mapToDouble(Airport::getOccupancyPct)
                .average()
                .orElse(0.0);

        long totalFlights     = flightRepository.count();
        long scheduledFlights = flightRepository.findByStatus(FlightStatus.SCHEDULED).size();
        long inFlight         = flightRepository.findByStatus(FlightStatus.IN_FLIGHT).size();

        return ResponseEntity.ok(new SystemStatusDto(
                total, normal, alerta, critico,
                avgOccupancy,
                totalFlights, scheduledFlights, inFlight
        ));
    }

    private int[] getThresholds() {
        return configRepository.findAll().stream()
                .findFirst()
                .map(c -> new int[]{c.getNormalThresholdPct(), c.getWarningThresholdPct()})
                .orElse(new int[]{70, 90});
    }
}
