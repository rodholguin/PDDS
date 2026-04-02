package com.tasfb2b.controller;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.dto.ShipmentDetailDto;
import com.tasfb2b.dto.TravelStopDto;
import com.tasfb2b.model.*;
import com.tasfb2b.repository.*;
import com.tasfb2b.service.RoutePlannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
@Tag(name = "Envíos", description = "Gestión de grupos de maletas en tránsito")
public class ShipmentController {

    private final ShipmentRepository   shipmentRepository;
    private final AirportRepository    airportRepository;
    private final TravelStopRepository travelStopRepository;
    private final SimulationConfigRepository configRepository;
    private final RoutePlannerService  routePlannerService;

    // ── Listados ──────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Listar envíos con paginación y filtro opcional por estado")
    public ResponseEntity<Page<Shipment>> list(
            @RequestParam(required = false) ShipmentStatus status,
            @PageableDefault(size = 20, sort = "registrationDate") Pageable pageable) {

        Page<Shipment> page = status != null
                ? shipmentRepository.findByStatus(status, pageable)
                : shipmentRepository.findAll(pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/overdue")
    @Operation(summary = "Envíos que han superado su deadline y no están entregados")
    public ResponseEntity<List<Shipment>> overdue() {
        return ResponseEntity.ok(
                shipmentRepository.findOverdueShipments(LocalDateTime.now()));
    }

    @GetMapping("/critical")
    @Operation(summary = "Envíos en estado CRITICAL o DELAYED")
    public ResponseEntity<List<Shipment>> critical() {
        return ResponseEntity.ok(shipmentRepository.findCriticalShipments());
    }

    // ── Detalle ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de un envío con su plan de paradas")
    public ResponseEntity<ShipmentDetailDto> getById(@PathVariable Long id) {
        Shipment s = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(s);

        return ResponseEntity.ok(toDetailDto(s, stops));
    }

    // ── Creación manual ───────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Crear un envío manualmente y planificar su ruta")
    public ResponseEntity<ShipmentDetailDto> create(
            @Valid @RequestBody ShipmentCreateDto dto) {

        Airport origin = airportRepository.findByIcaoCode(dto.originIcao())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aeropuerto origen no encontrado: " + dto.originIcao()));
        Airport dest = airportRepository.findByIcaoCode(dto.destinationIcao())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aeropuerto destino no encontrado: " + dto.destinationIcao()));

        LocalDateTime regDate = dto.registrationDate() != null
                ? dto.registrationDate()
                : LocalDateTime.now();

        Shipment shipment = Shipment.builder()
                .shipmentCode(generateCode())
                .airlineName(dto.airlineName())
                .originAirport(origin)
                .destinationAirport(dest)
                .luggageCount(dto.luggageCount())
                .registrationDate(regDate)
                .status(ShipmentStatus.PENDING)
                .progressPercentage(0.0)
                .build();

        shipmentRepository.save(shipment);

        // Planificar ruta con el algoritmo solicitado (o el activo en config)
        String algo = dto.algorithmName() != null
                ? dto.algorithmName()
                : getActiveAlgoName();
        List<TravelStop> stops = routePlannerService.planShipment(shipment, algo);

        return ResponseEntity
                .created(URI.create("/api/shipments/" + shipment.getId()))
                .body(toDetailDto(shipment, stops));
    }

    // ── Replanificación ───────────────────────────────────────────────────────

    @PutMapping("/{id}/replan")
    @Operation(summary = "Replanificar la ruta de un envío desde su última parada fallida")
    public ResponseEntity<ShipmentDetailDto> replan(@PathVariable Long id) {
        List<TravelStop> newStops = routePlannerService.replanShipment(id);
        Shipment s = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));
        return ResponseEntity.ok(toDetailDto(s, newStops));
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private ShipmentDetailDto toDetailDto(Shipment s, List<TravelStop> stops) {
        List<TravelStopDto> stopDtos = stops.stream().map(ts -> new TravelStopDto(
                ts.getId(),
                ts.getStopOrder(),
                ts.getAirport().getIcaoCode(),
                ts.getAirport().getCity(),
                ts.getFlight() != null ? ts.getFlight().getFlightCode() : null,
                ts.getScheduledArrival(),
                ts.getActualArrival(),
                ts.getStopStatus()
        )).toList();

        return new ShipmentDetailDto(
                s.getId(),
                s.getShipmentCode(),
                s.getAirlineName(),
                s.getOriginAirport().getIcaoCode(),
                s.getOriginAirport().getCity(),
                s.getDestinationAirport().getIcaoCode(),
                s.getDestinationAirport().getCity(),
                s.getLuggageCount(),
                s.getRegistrationDate(),
                s.getDeadline(),
                s.getStatus(),
                s.getProgressPercentage(),
                s.getIsInterContinental(),
                stopDtos
        );
    }

    private String generateCode() {
        int  year  = LocalDateTime.now().getYear();
        long count = shipmentRepository.count() + 1;
        return String.format("ENV-%d-%03d", year, count);
    }

    private String getActiveAlgoName() {
        return configRepository.findAll().stream()
                .findFirst()
                .map(c -> c.getPrimaryAlgorithm() == AlgorithmType.ANT_COLONY
                        ? "Ant Colony Optimization"
                        : "Genetic Algorithm")
                .orElse("Genetic Algorithm");
    }
}
