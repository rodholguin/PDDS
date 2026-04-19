package com.tasfb2b.controller;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.dto.ShipmentDetailDto;
import com.tasfb2b.dto.ShipmentFeasibilityDto;
import com.tasfb2b.dto.ShipmentLegDto;
import com.tasfb2b.dto.ShipmentPlanningEventDto;
import com.tasfb2b.dto.ShipmentUpcomingDto;
import com.tasfb2b.dto.TravelStopDto;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.FlightRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
@Tag(name = "Envíos", description = "Gestión de grupos de maletas en tránsito")
public class ShipmentController {

    private final ShipmentRepository shipmentRepository;
    private final FlightRepository flightRepository;
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
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "false") boolean fromDate,
            @RequestParam(required = false, defaultValue = "false") boolean currentOnly,
            @PageableDefault(size = 20, sort = "registrationDate") Pageable pageable
    ) {
        boolean hasAdvancedFilters = airline != null || origin != null || destination != null || code != null;
        LocalDateTime dateFrom = null;
        LocalDateTime dateTo = null;
        if (date != null) {
            dateFrom = date.atStartOfDay();
            dateTo = fromDate ? null : date.plusDays(1).atStartOfDay();
        }

        Page<Shipment> page;
        if (currentOnly && dateFrom != null && dateTo != null) {
            page = shipmentRepository.searchVisibleForOperationalDay(
                    normalizeUpper(airline),
                    normalizeUpper(origin),
                    normalizeUpper(destination),
                    status,
                    normalizeLikeUpper(code),
                    dateFrom,
                    dateTo,
                    pageable
            );
        } else if (hasAdvancedFilters || dateFrom != null) {
            if (dateFrom != null && dateTo != null) {
                page = shipmentRepository.searchShipmentsPageOnDate(
                        normalizeUpper(airline),
                        normalizeUpper(origin),
                        normalizeUpper(destination),
                        status,
                        normalizeLikeUpper(code),
                        dateFrom,
                        dateTo,
                        pageable
                );
            } else if (dateFrom != null) {
                page = shipmentRepository.searchShipmentsPageFromDate(
                        normalizeUpper(airline),
                        normalizeUpper(origin),
                        normalizeUpper(destination),
                        status,
                        normalizeLikeUpper(code),
                        dateFrom,
                        pageable
                );
            } else {
                page = shipmentRepository.searchShipmentsPage(
                        normalizeUpper(airline),
                        normalizeUpper(origin),
                        normalizeUpper(destination),
                        status,
                        normalizeLikeUpper(code),
                        pageable
                );
            }
        } else if (status != null) {
            page = shipmentRepository.findByStatus(status, pageable);
        } else {
            page = shipmentRepository.findAll(pageable);
        }

        return ResponseEntity.ok(page);
    }

    @GetMapping("/upcoming")
    @Operation(summary = "Listar envíos por próxima salida de vuelo")
    public ResponseEntity<Page<ShipmentUpcomingDto>> upcoming(
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) LocalDate date,
            @PageableDefault(size = 20, sort = "registrationDate") Pageable pageable
    ) {
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        LocalDateTime dateFrom = effectiveDate.atStartOfDay();
        LocalDateTime dateTo = effectiveDate.plusDays(1).atStartOfDay();
        String normalizedCode = normalizeLikeUpper(code);
        int candidateSize = Math.max((int) pageable.getOffset() + pageable.getPageSize() * 2, 20);
        Pageable candidatePageable = PageRequest.of(0, candidateSize);

        Page<Shipment> candidates = shipmentRepository.findUpcomingShipmentCandidates(
                status == null ? ShipmentStatus.PENDING : status,
                normalizeUpper(origin),
                normalizeUpper(destination),
                normalizedCode,
                dateFrom,
                dateTo,
                candidatePageable
        );

        List<Airport> candidateOrigins = candidates.getContent().stream()
                .map(Shipment::getOriginAirport)
                .distinct()
                .toList();
        LocalDateTime flightsFrom = candidates.getContent().stream()
                .map(Shipment::getRegistrationDate)
                .min(LocalDateTime::compareTo)
                .orElse(dateFrom);
        LocalDateTime flightsTo = candidates.getContent().stream()
                .map(shipment -> shipment.getDeadline() == null ? shipment.getRegistrationDate().plusDays(2) : shipment.getDeadline().plusDays(1))
                .max(LocalDateTime::compareTo)
                .orElse(dateTo.plusDays(2));
        Map<Long, List<Flight>> flightsByOriginId = candidateOrigins.isEmpty()
                ? Map.of()
                : flightRepository.findSchedulableFlightsByOriginsAndWindow(candidateOrigins, flightsFrom, flightsTo).stream()
                        .collect(java.util.stream.Collectors.groupingBy(flight -> flight.getOriginAirport().getId()));
        Map<Long, List<TravelStop>> stopsByShipmentId = candidates.getContent().isEmpty()
                ? Map.of()
                : travelStopRepository.findByShipmentInOrderByShipmentIdAscStopOrderAsc(candidates.getContent()).stream()
                        .collect(java.util.stream.Collectors.groupingBy(stop -> stop.getShipment().getId()));

        List<ShipmentUpcomingDto> ordered = candidates.getContent().stream()
                .map(shipment -> toUpcomingDto(shipment, flightsByOriginId, stopsByShipmentId.getOrDefault(shipment.getId(), List.of())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ShipmentUpcomingDto::nextFlightDeparture, Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();

        int fromIndex = Math.min((int) pageable.getOffset(), ordered.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), ordered.size());
        List<ShipmentUpcomingDto> content = ordered.subList(fromIndex, toIndex);

        return ResponseEntity.ok(new PageImpl<>(content, pageable, candidates.getTotalElements()));
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

    @PostMapping("/repair-delivered")
    @Operation(summary = "Reparar envíos DELIVERED con stops/legs residuales")
    public ResponseEntity<Map<String, Object>> repairDelivered() {
        int repaired = routePlannerService.repairDeliveredShipments();
        return ResponseEntity.ok(Map.of(
                "message", "Envíos entregados reparados",
                "repaired", repaired
        ));
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
                    stop.getFlight() == null ? null : stop.getFlight().getScheduledDeparture(),
                    stop.getScheduledArrival(),
                    stop.getActualArrival(),
                    stop.getStopStatus()
            ));
        }

        List<ShipmentLegDto> legs = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            TravelStop fromStop = stops.get(i - 1);
            TravelStop toStop = stops.get(i);
            Flight flight = toStop.getFlight();
            ShipmentLegDto leg = new ShipmentLegDto(
                    flight == null ? null : flight.getId(),
                    flight == null ? null : flight.getFlightCode(),
                    fromStop.getAirport().getIcaoCode(),
                    fromStop.getAirport().getCity(),
                    toStop.getAirport().getIcaoCode(),
                    toStop.getAirport().getCity(),
                    flight == null ? null : flight.getScheduledDeparture(),
                    flight == null ? toStop.getScheduledArrival() : flight.getScheduledArrival(),
                    toStop.getActualArrival(),
                    flight == null ? null : flight.getStatus(),
                    toStop.getStopStatus(),
                    toStop.getStopStatus() == com.tasfb2b.model.StopStatus.IN_TRANSIT,
                    toStop.getStopStatus() == com.tasfb2b.model.StopStatus.PENDING
                            && legs.stream().noneMatch(ShipmentLegDto::next)
            );
            legs.add(leg);
        }

        ShipmentLegDto currentLeg = legs.stream().filter(ShipmentLegDto::current).findFirst().orElse(null);
        ShipmentLegDto nextLeg = legs.stream().filter(ShipmentLegDto::next).findFirst().orElse(null);
        String lastConfirmedNode = stops.stream()
                .filter(stop -> stop.getStopStatus() == com.tasfb2b.model.StopStatus.COMPLETED)
                .reduce((first, second) -> second)
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse(shipment.getOriginAirport().getIcaoCode());
        LocalDateTime estimatedDestinationArrival = legs.stream()
                .map(ShipmentLegDto::scheduledArrival)
                .filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(shipment.getDeadline());

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
                lastConfirmedNode,
                currentLeg,
                nextLeg,
                estimatedDestinationArrival,
                stopDtos,
                legs,
                shipmentAuditService.getAudit(shipment)
        );
    }

    private ShipmentUpcomingDto toUpcomingDto(Shipment shipment, Map<Long, List<Flight>> flightsByOriginId, List<TravelStop> stops) {
        TravelStop nextFlightStop = stops.stream()
                .filter(stop -> stop.getStopStatus() == com.tasfb2b.model.StopStatus.PENDING)
                .filter(stop -> stop.getFlight() != null)
                .min(Comparator.comparing(TravelStop::getStopOrder))
                .orElse(null);

        Flight flight = nextFlightStop == null ? null : nextFlightStop.getFlight();
        if (flight == null) {
            LocalDateTime from = shipment.getRegistrationDate();
            LocalDateTime to = shipment.getDeadline() == null ? from.plusDays(2) : shipment.getDeadline().plusDays(1);
            flight = flightsByOriginId.getOrDefault(shipment.getOriginAirport().getId(), List.of()).stream()
                    .filter(candidate -> !candidate.getScheduledDeparture().isBefore(from))
                    .filter(candidate -> candidate.getScheduledDeparture().isBefore(to))
                    .filter(candidate -> (candidate.getMaxCapacity() - candidate.getCurrentLoad()) >= shipment.getLuggageCount())
                    .min(Comparator.comparing(Flight::getScheduledDeparture))
                    .orElse(null);
        }

        if (flight == null) {
            return null;
        }

        return new ShipmentUpcomingDto(
                shipment.getId(),
                shipment.getShipmentCode(),
                shipment.getAirlineName(),
                Map.of("icaoCode", shipment.getOriginAirport().getIcaoCode()),
                Map.of("icaoCode", shipment.getDestinationAirport().getIcaoCode()),
                shipment.getLuggageCount(),
                shipment.getRegistrationDate(),
                shipment.getDeadline(),
                shipment.getDeliveredAt(),
                shipment.getStatus(),
                shipment.getProgressPercentage(),
                shipment.getIsInterContinental(),
                flight.getId(),
                flight.getFlightCode(),
                flight.getScheduledDeparture()
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
        if (message.contains("Simulated Annealing")) return "Simulated Annealing";
        return "N/A";
    }
}
