package com.tasfb2b.controller;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.dto.ShipmentDetailDto;
import com.tasfb2b.dto.ShipmentFeasibilityDto;
import com.tasfb2b.dto.ShipmentPlanningEventDto;
import com.tasfb2b.dto.TravelStopDto;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.ShipmentAuditService;
import com.tasfb2b.service.ShipmentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
@Tag(name = "Envíos", description = "Gestión de grupos de maletas en tránsito")
public class ShipmentController {

    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final ShipmentAuditLogRepository shipmentAuditLogRepository;
    private final RoutePlannerService routePlannerService;
    private final ShipmentAuditService shipmentAuditService;
    private final ShipmentOrchestratorService shipmentOrchestratorService;

    @GetMapping
    @Operation(summary = "Listar envíos con paginación y filtros")
    public ResponseEntity<Page<Shipment>> list(
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String airline,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String code,
            @PageableDefault(size = 20, sort = "registrationDate") Pageable pageable
    ) {
        boolean hasAdvancedFilters = airline != null || origin != null || destination != null || code != null;

        Page<Shipment> page;
        if (hasAdvancedFilters) {
            page = shipmentRepository.searchShipmentsPage(
                    normalizeUpper(airline),
                    normalizeUpper(origin),
                    normalizeUpper(destination),
                    status,
                    normalizeLikeUpper(code),
                    pageable
            );
        } else if (status != null) {
            page = shipmentRepository.findByStatus(status, pageable);
        } else {
            page = shipmentRepository.findAll(pageable);
        }

        return ResponseEntity.ok(page);
    }

    @GetMapping("/overdue")
    @Operation(summary = "Envíos que superaron deadline y no están entregados")
    public ResponseEntity<List<Shipment>> overdue() {
        return ResponseEntity.ok(shipmentRepository.findOverdueShipments(java.time.LocalDateTime.now()));
    }

    @GetMapping("/critical")
    @Operation(summary = "Envíos en estado CRITICAL o DELAYED")
    public ResponseEntity<List<Shipment>> critical() {
        return ResponseEntity.ok(shipmentRepository.findCriticalShipments());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle completo de un envío")
    public ResponseEntity<ShipmentDetailDto> getById(@PathVariable Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        return ResponseEntity.ok(toDetailDto(shipment, stops));
    }

    @PostMapping
    @Operation(summary = "Crear un envío y planificar su ruta automáticamente")
    public ResponseEntity<ShipmentDetailDto> create(@Valid @RequestBody ShipmentCreateDto dto) {
        Shipment shipment = shipmentOrchestratorService.createAndPlan(dto);
        List<TravelStop> plannedStops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        return ResponseEntity
                .created(URI.create("/api/shipments/" + shipment.getId()))
                .body(toDetailDto(shipment, plannedStops));
    }

    @PostMapping("/check-feasibility")
    @Operation(summary = "Validar factibilidad de ruta antes de registrar el envío")
    public ResponseEntity<ShipmentFeasibilityDto> checkFeasibility(@Valid @RequestBody ShipmentCreateDto dto) {
        return ResponseEntity.ok(shipmentOrchestratorService.checkFeasibility(dto));
    }

    @PutMapping("/{id}/replan")
    @Operation(summary = "Replanificar ruta desde próxima parada pendiente")
    public ResponseEntity<ShipmentDetailDto> replan(@PathVariable Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        List<TravelStop> newStops = routePlannerService.replanShipment(id);
        return ResponseEntity.ok(toDetailDto(shipment, newStops));
    }

    @PutMapping("/{id}/deliver")
    @Operation(summary = "Confirmar entrega final de envío")
    public ResponseEntity<ShipmentDetailDto> deliver(@PathVariable Long id) {
        Shipment delivered = routePlannerService.markDelivered(id);
        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(delivered);
        return ResponseEntity.ok(toDetailDto(delivered, stops));
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Generar comprobante de recepción del envío")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        String payload = String.join("\n",
                "COMPROBANTE DE RECEPCION",
                "Numero envio: " + shipment.getShipmentCode(),
                "Origen: " + shipment.getOriginAirport().getIcaoCode(),
                "Destino: " + shipment.getDestinationAirport().getIcaoCode(),
                "Fecha recepcion: " + shipment.getRegistrationDate(),
                "Plazo maximo entrega: " + shipment.getDeadline()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt-" + shipment.getShipmentCode() + ".txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(payload.getBytes(StandardCharsets.UTF_8));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar datos de envío si no fue despachado")
    public ResponseEntity<ShipmentDetailDto> update(@PathVariable Long id, @Valid @RequestBody ShipmentCreateDto dto) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        if (shipment.getStatus() != ShipmentStatus.PENDING) {
            throw new IllegalArgumentException("Solo se puede editar un envío en espera en nodo origen");
        }

        shipment.setAirlineName(dto.airlineName().trim());
        shipment.setLuggageCount(dto.luggageCount());
        if (dto.registrationDate() != null) {
            shipment.setRegistrationDate(dto.registrationDate());
        }
        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.EVENT_INJECTED,
                "Datos de envío actualizados por operador",
                shipment.getOriginAirport(),
                null
        );

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        return ResponseEntity.ok(toDetailDto(shipment, stops));
    }

    @GetMapping("/{id}/planning-history")
    @Operation(summary = "Historial de planificaciones y replanificaciones de un envío")
    public ResponseEntity<List<ShipmentPlanningEventDto>> planningHistory(@PathVariable Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + id));

        List<ShipmentPlanningEventDto> events = shipmentAuditLogRepository.findByShipmentOrderByEventAtAsc(shipment)
                .stream()
                .filter(log -> log.getEventType() == ShipmentAuditType.ROUTE_PLANNED || log.getEventType() == ShipmentAuditType.ROUTE_REPLANNED)
                .map(log -> new ShipmentPlanningEventDto(
                        log.getEventType().name(),
                        log.getEventAt(),
                        log.getEventType() == ShipmentAuditType.ROUTE_REPLANNED ? "Replanificacion" : "Planificacion inicial",
                        extractAlgorithm(log.getMessage()),
                        log.getMessage()
                ))
                .toList();

        return ResponseEntity.ok(events);
    }

    private ShipmentDetailDto toDetailDto(Shipment shipment, List<TravelStop> stops) {
        List<TravelStopDto> stopDtos = new ArrayList<>();
        for (TravelStop stop : stops) {
            stopDtos.add(new TravelStopDto(
                    stop.getId(),
                    stop.getStopOrder(),
                    stop.getAirport().getIcaoCode(),
                    stop.getAirport().getCity(),
                    stop.getAirport().getLatitude(),
                    stop.getAirport().getLongitude(),
                    stop.getFlight() == null ? null : stop.getFlight().getFlightCode(),
                    stop.getScheduledArrival(),
                    stop.getActualArrival(),
                    stop.getStopStatus()
            ));
        }

        return new ShipmentDetailDto(
                shipment.getId(),
                shipment.getShipmentCode(),
                shipment.getAirlineName(),
                shipment.getOriginAirport().getIcaoCode(),
                shipment.getOriginAirport().getCity(),
                shipment.getDestinationAirport().getIcaoCode(),
                shipment.getDestinationAirport().getCity(),
                shipment.getLuggageCount(),
                shipment.getRegistrationDate(),
                shipment.getDeadline(),
                shipment.getDeliveredAt(),
                shipment.getStatus(),
                shipment.getProgressPercentage(),
                shipment.getIsInterContinental(),
                stopDtos,
                shipmentAuditService.getAudit(shipment)
        );
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeUpper(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private String normalizeLikeUpper(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : "%" + normalized.toUpperCase() + "%";
    }

    private String extractAlgorithm(String message) {
        if (message == null) return "N/A";
        if (message.contains("Ant Colony")) return "Ant Colony Optimization";
        if (message.contains("Genetic")) return "Genetic Algorithm";
        return "N/A";
    }
}
