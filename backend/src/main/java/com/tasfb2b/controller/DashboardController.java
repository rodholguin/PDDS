package com.tasfb2b.controller;

import com.tasfb2b.dto.DashboardKpisDto;
import com.tasfb2b.dto.DashboardOverviewDto;
import com.tasfb2b.dto.NodeDetailDto;
import com.tasfb2b.dto.MapLiveShipmentDto;
import com.tasfb2b.dto.MapLiveFlightDto;
import com.tasfb2b.dto.RouteNetworkEdgeDto;
import com.tasfb2b.dto.ShipmentSearchResultDto;
import com.tasfb2b.dto.ShipmentSummaryDto;
import com.tasfb2b.dto.SystemStatusDto;
import org.springframework.data.domain.Page;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.AirportStatus;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.DashboardShipmentCacheService;
import com.tasfb2b.service.OperationalAlertService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.SimulationEngineService;
import com.tasfb2b.service.SimulationRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "KPIs y estado global del sistema para el mapa")
public class DashboardController {

    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final SimulationConfigRepository configRepository;
    private final TravelStopRepository travelStopRepository;
    private final RoutePlannerService routePlannerService;
    private final CollapseMonitorService collapseMonitorService;
    private final ShipmentAuditLogRepository shipmentAuditLogRepository;
    private final SimulationRuntimeService simulationRuntimeService;
    private final DashboardShipmentCacheService dashboardShipmentCacheService;
    private final SimulationEngineService simulationEngineService;
    private final OperationalAlertService operationalAlertService;

    public DashboardController(
            AirportRepository airportRepository,
            FlightRepository flightRepository,
            ShipmentRepository shipmentRepository,
            SimulationConfigRepository configRepository,
            TravelStopRepository travelStopRepository,
            RoutePlannerService routePlannerService,
            CollapseMonitorService collapseMonitorService,
            ShipmentAuditLogRepository shipmentAuditLogRepository,
            SimulationRuntimeService simulationRuntimeService,
            DashboardShipmentCacheService dashboardShipmentCacheService,
            SimulationEngineService simulationEngineService,
            OperationalAlertService operationalAlertService
    ) {
        this.airportRepository = airportRepository;
        this.flightRepository = flightRepository;
        this.shipmentRepository = shipmentRepository;
        this.configRepository = configRepository;
        this.travelStopRepository = travelStopRepository;
        this.routePlannerService = routePlannerService;
        this.collapseMonitorService = collapseMonitorService;
        this.shipmentAuditLogRepository = shipmentAuditLogRepository;
        this.simulationRuntimeService = simulationRuntimeService;
        this.dashboardShipmentCacheService = dashboardShipmentCacheService;
        this.simulationEngineService = simulationEngineService;
        this.operationalAlertService = operationalAlertService;
    }

    @GetMapping("/kpis")
    @Operation(summary = "KPIs principales para la cabecera del dashboard")
    public ResponseEntity<DashboardKpisDto> getKpis() {
        int[] thresholds = getThresholds();
        LocalDateTime now = effectiveNow();
        LocalDateTime todayStart = operationalDayStart(now);
        LocalDateTime tomorrow = todayStart.plusDays(1);
        long totalShipments = shipmentRepository.countByRegistrationDateBetween(todayStart, tomorrow);
        long activeShipments = shipmentRepository.countByStatusIn(List.of(ShipmentStatus.PENDING, ShipmentStatus.IN_ROUTE));
        long inRouteShipments = shipmentRepository.countInRouteBetween(todayStart, tomorrow);
        long criticalShipments = shipmentRepository.countAtRiskShipmentsBetween(todayStart, tomorrow, now);
        long delivered = shipmentRepository.countDeliveredBetween(todayStart, tomorrow);

        double sysLoad = collapseMonitorService.computeSystemLoad();
        double collapseRisk = routePlannerService.getCollapseRisk();

        List<Airport> airports = airportRepository.findAll();
        int totalAirports = airports.size();

        long alerta = airports.stream()
                .filter(airport -> airport.getStatus(thresholds[0], thresholds[1]) == AirportStatus.ALERTA)
                .count();
        long critico = airports.stream()
                .filter(airport -> airport.getStatus(thresholds[0], thresholds[1]) == AirportStatus.CRITICO)
                .count();

        return ResponseEntity.ok(new DashboardKpisDto(
                totalShipments,
                activeShipments,
                inRouteShipments,
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
    @Operation(summary = "Distribucion de aeropuertos y vuelos por estado")
    public ResponseEntity<SystemStatusDto> getSystemStatus() {
        int[] thresholds = getThresholds();
        SimulationConfig config = currentConfig();

        List<Airport> airports = airportRepository.findAll();
        long total = airports.size();
        long normal = airports.stream()
                .filter(airport -> airport.getStatus(thresholds[0], thresholds[1]) == AirportStatus.NORMAL)
                .count();
        long alerta = airports.stream()
                .filter(airport -> airport.getStatus(thresholds[0], thresholds[1]) == AirportStatus.ALERTA)
                .count();
        long critico = airports.stream()
                .filter(airport -> airport.getStatus(thresholds[0], thresholds[1]) == AirportStatus.CRITICO)
                .count();

        double avgOccupancy = airports.stream()
                .mapToDouble(Airport::getOccupancyPct)
                .average()
                .orElse(0.0);

        LocalDateTime now = effectiveNow();
        LocalDateTime visibleFrom = activeVisibilityStart(config, now);
        List<Flight> visibleFlights = flightRepository.findFlightsWithinWindow(visibleFrom, now.plusDays(1));
        long totalFlights = visibleFlights.size();
        long scheduledFlights = visibleFlights.stream().filter(flight -> flight.getStatus() == FlightStatus.SCHEDULED).count();
        long inFlight = visibleFlights.stream().filter(flight -> flight.getStatus() == FlightStatus.IN_FLIGHT).count();

        return ResponseEntity.ok(new SystemStatusDto(
                total,
                normal,
                alerta,
                critico,
                avgOccupancy,
                totalFlights,
                scheduledFlights,
                inFlight
        ));
    }

    @GetMapping("/overview")
    @Operation(summary = "Resumen operacional extendido del panel principal")
    public ResponseEntity<DashboardOverviewDto> getOverview() {
        SimulationConfig config = currentConfig();
        LocalDateTime now = effectiveNow();
        LocalDateTime todayStart = operationalDayStart(now);
        LocalDateTime tomorrow = todayStart.plusDays(1);
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime visibleFrom = activeVisibilityStart(config, now);

        long activeFlights = isPeriodSimulation(config)
                ? flightRepository.countActiveFlightsSince(now, visibleFrom)
                : flightRepository.countLoadedActiveFlightsAtWithinDay(now, todayStart, tomorrow);
        long nextScheduledFlights = Math.min(25L, flightRepository.countLoadedScheduledFlightsBetween(now, tomorrow));
        long inRoute = isPeriodSimulation(config)
                ? shipmentRepository.countInRouteSince(visibleFrom)
                : shipmentRepository.countVisibleForMapWithinDay(todayStart, tomorrow);
        long totalToday = shipmentRepository.countByRegistrationDateBetween(todayStart, tomorrow);

        long deliveredToday = shipmentRepository.countDeliveredBetween(todayStart, tomorrow);
        long deliveredOnTimeToday = shipmentRepository.countDeliveredOnTimeBetween(todayStart, tomorrow);
        long deliveredYesterday = shipmentRepository.countDeliveredBetween(yesterdayStart, todayStart);
        long deliveredOnTimeYesterday = shipmentRepository.countDeliveredOnTimeBetween(yesterdayStart, todayStart);

        double slaToday = deliveredToday == 0 ? 100.0 : (deliveredOnTimeToday * 100.0) / deliveredToday;
        double slaYesterday = deliveredYesterday == 0 ? 100.0 : (deliveredOnTimeYesterday * 100.0) / deliveredYesterday;
        double delta = deliveredYesterday == 0
                ? (slaToday > 0 ? 100.0 : 0.0)
                : ((slaToday - slaYesterday) / Math.max(0.0001, slaYesterday)) * 100.0;

        long unresolved = operationalAlertService.countActiveAlertsForCurrentOperationalDay();
        long overdue = shipmentRepository.countOverdueShipmentsBetween(todayStart, tomorrow, now);
        long atRisk = shipmentRepository.countAtRiskShipmentsBetween(todayStart, tomorrow, now);
        long stalled = shipmentRepository.countShipmentsWithoutMovementBetween(todayStart, tomorrow, now.minusHours(6));

        long intra = shipmentRepository.countActiveByRouteTypeBetween(false, todayStart, tomorrow);
        long inter = shipmentRepository.countActiveByRouteTypeBetween(true, todayStart, tomorrow);
        long totalActive = intra + inter;
        double intraPct = totalActive == 0 ? 0.0 : (intra * 100.0) / totalActive;
        double interPct = totalActive == 0 ? 0.0 : (inter * 100.0) / totalActive;

        List<Airport> airports = airportRepository.findAll();
        long availableNodes = airports.stream().filter(airport -> airport.getOccupancyPct() < 90.0).count();
        double availableNodesPct = airports.isEmpty() ? 0.0 : (availableNodes * 100.0) / airports.size();

        double avgDeliveryHours = shipmentRepository.avgDeliveryHoursBetween(todayStart, tomorrow);
        double avgCommittedHours = shipmentRepository.avgCommittedHoursBetween(todayStart, tomorrow);
        double avgDeliveryDeltaHours = avgDeliveryHours - avgCommittedHours;

        long replanningsToday = shipmentAuditLogRepository.countByEventTypeAndPeriod(
                com.tasfb2b.model.ShipmentAuditType.ROUTE_REPLANNED,
                todayStart,
                tomorrow
        );

        return ResponseEntity.ok(new DashboardOverviewDto(
                activeFlights,
                nextScheduledFlights,
                inRoute,
                totalToday,
                inRoute,
                deliveredToday,
                slaToday,
                delta,
                unresolved,
                overdue,
                atRisk,
                stalled,
                intra,
                inter,
                intraPct,
                interPct,
                availableNodesPct,
                avgDeliveryHours,
                avgCommittedHours,
                avgDeliveryDeltaHours,
                replanningsToday
        ));
    }

    @GetMapping("/shipments")
    @Operation(summary = "Listado resumido de envios para panel y mapa")
    public ResponseEntity<Page<ShipmentSummaryDto>> getShipmentSummaries(
            @RequestParam(required = false) String airline,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "50") Integer size
    ) {
        LocalDateTime now = effectiveNow();
        LocalDate effectiveDate = date == null ? now.toLocalDate() : date;
        LocalDateTime dayStart = effectiveDate.atStartOfDay();
        LocalDateTime dayEnd = effectiveDate.plusDays(1).atStartOfDay();
        int safePage = Math.max(0, page == null ? 0 : page);
        int safeSize = Math.max(1, Math.min(200, size == null ? 50 : size));
        Page<Shipment> shipments = shipmentRepository.searchVisibleForOperationalDay(
                normalizeUpper(airline),
                normalizeUpper(origin),
                normalizeUpper(destination),
                status,
                null,
                dayStart,
                dayEnd,
                PageRequest.of(safePage, safeSize)
        );

        List<ShipmentSummaryDto> response = shipments.getContent().stream()
                .map(shipment -> toSummaryDto(shipment, now))
                .toList();

        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(response, shipments.getPageable(), shipments.getTotalElements()));
    }

    @GetMapping("/map-live")
    @Operation(summary = "Feed liviano para aviones en ruta del mapa")
    public ResponseEntity<List<MapLiveShipmentDto>> mapLive(
            @RequestParam(required = false) Integer limit
    ) {
        SimulationConfig config = currentConfig();
        LocalDateTime now = effectiveNow();
        LocalDateTime visibleFrom = activeVisibilityStart(config, now);

        List<Shipment> inRoute = isPeriodSimulation(config)
                ? shipmentRepository.findInRouteSince(visibleFrom)
                : shipmentRepository.findInRouteWithinDay(operationalDayStart(now), operationalDayStart(now).plusDays(1));
        if (limit == null || limit <= 0) {
            inRoute = inRoute.stream()
                    .sorted(Comparator.comparing(Shipment::getRegistrationDate))
                    .toList();
        } else {
            inRoute = inRoute.stream()
                    .sorted(Comparator.comparing(Shipment::getRegistrationDate))
                    .limit(limit)
                    .toList();
        }

        if (inRoute.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Map<Long, List<TravelStop>> stopsIndex = travelStopRepository
                .findByStopStatusInAndShipmentStatusInOrderByShipmentIdAscStopOrderAsc(
                        List.of(StopStatus.PENDING, StopStatus.IN_TRANSIT, StopStatus.COMPLETED),
                        List.of(ShipmentStatus.IN_ROUTE, ShipmentStatus.DELAYED, ShipmentStatus.CRITICAL)
                )
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(ts -> ts.getShipment().getId()));

        List<MapLiveShipmentDto> rows = new ArrayList<>();
        for (Shipment shipment : inRoute) {
            List<TravelStop> stops = stopsIndex.getOrDefault(shipment.getId(), List.of());
            Position current = computeCurrentPosition(shipment, stops, now);

            Optional<TravelStop> nextStop = stops.stream()
                    .filter(stop -> stop.getStopStatus() == StopStatus.IN_TRANSIT || stop.getStopStatus() == StopStatus.PENDING)
                    .findFirst();

            double nextLat = nextStop.map(stop -> stop.getAirport().getLatitude()).orElse(shipment.getDestinationAirport().getLatitude());
            double nextLon = nextStop.map(stop -> stop.getAirport().getLongitude()).orElse(shipment.getDestinationAirport().getLongitude());

            rows.add(new MapLiveShipmentDto(
                    shipment.getId(),
                    shipment.getShipmentCode(),
                    shipment.getOriginAirport().getIcaoCode(),
                    shipment.getDestinationAirport().getIcaoCode(),
                    current.latitude(),
                    current.longitude(),
                    nextLat,
                    nextLon,
                    shipment.getProgressPercentage() == null ? 0.0 : shipment.getProgressPercentage(),
                    shipment.getOriginAirport().getLatitude(),
                    shipment.getOriginAirport().getLongitude()
            ));
        }

        return ResponseEntity.ok(rows);
    }

    @GetMapping("/map-live-flights")
    @Operation(summary = "Feed liviano de vuelos activos para el mapa")
    public ResponseEntity<List<MapLiveFlightDto>> mapLiveFlights(@RequestParam(required = false) Integer limit) {
        SimulationConfig config = currentConfig();
        LocalDateTime now = effectiveNow();
        LocalDateTime visibleFrom = activeVisibilityStart(config, now);
        List<Flight> active = (isPeriodSimulation(config)
                ? flightRepository.findActiveFlightsSince(now, visibleFrom)
                : flightRepository.findActiveFlightsAtWithinDay(now, operationalDayStart(now), operationalDayStart(now).plusDays(1))).stream()
                .filter(flight -> flight.getCurrentLoad() != null && flight.getCurrentLoad() > 0)
                .toList();

        List<Flight> rows = (limit == null || limit <= 0) ? active : active.stream().limit(limit).toList();
        List<MapLiveFlightDto> dto = new ArrayList<>();
        for (Flight flight : rows) {
            Airport origin = flight.getOriginAirport();
            Airport destination = flight.getDestinationAirport();
            long total = Math.max(1L, Duration.between(flight.getScheduledDeparture(), flight.getScheduledArrival()).toSeconds());
            long elapsed = Math.max(0L, Duration.between(flight.getScheduledDeparture(), now).toSeconds());
            double ratio = Math.max(0.0, Math.min(1.0, elapsed / (double) total));

            double fromLon = normalizeLongitude(origin.getLongitude());
            double toLon = nearestWrappedLongitude(normalizeLongitude(destination.getLongitude()), fromLon);
            double lon = normalizeLongitude(fromLon + (toLon - fromLon) * ratio);

            double fromMercY = mercatorY(origin.getLatitude());
            double toMercY = mercatorY(destination.getLatitude());
            double lat = inverseMercatorY(fromMercY + (toMercY - fromMercY) * ratio);

            dto.add(new MapLiveFlightDto(
                    flight.getId(),
                    flight.getFlightCode(),
                    origin.getIcaoCode(),
                    destination.getIcaoCode(),
                    lat,
                    lon,
                    origin.getLatitude(),
                    origin.getLongitude(),
                    destination.getLatitude(),
                    destination.getLongitude(),
                    flight.getLoadPct()
            ));
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/shipments/search")
    @Operation(summary = "Busqueda de envio por identificador unico")
    public ResponseEntity<ShipmentSearchResultDto> searchShipmentByCode(@RequestParam String code) {
        Shipment shipment = shipmentRepository.findByShipmentCode(code.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Envio no encontrado: " + code));

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        LocalDateTime now = effectiveNow();
        Position current = computeCurrentPosition(shipment, stops, now);
        String lastVisited = stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.COMPLETED)
                .max(Comparator.comparingInt(TravelStop::getStopOrder))
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse(shipment.getOriginAirport().getIcaoCode());

        String currentNode = stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.IN_TRANSIT)
                .findFirst()
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse(lastVisited);

        String remaining = computeRemainingTime(shipment.getDeadline(), now);
        boolean atRisk = isAtRisk(shipment, now);

        ShipmentSearchResultDto dto = new ShipmentSearchResultDto(
                shipment.getId(),
                shipment.getShipmentCode(),
                shipment.getAirlineName(),
                shipment.getOriginAirport().getIcaoCode(),
                shipment.getOriginAirport().getLatitude(),
                shipment.getOriginAirport().getLongitude(),
                shipment.getDestinationAirport().getIcaoCode(),
                shipment.getDestinationAirport().getLatitude(),
                shipment.getDestinationAirport().getLongitude(),
                shipment.getStatus(),
                lastVisited,
                currentNode,
                current.latitude(),
                current.longitude(),
                remaining,
                shipment.getProgressPercentage(),
                atRisk
        );

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/nodes/{icao}")
    @Operation(summary = "Detalle integral de nodo por codigo ICAO")
    public ResponseEntity<NodeDetailDto> getNodeDetails(
            @PathVariable String icao,
            @RequestParam(required = false) String date
    ) {
        Airport airport = airportRepository.findByIcaoCode(icao.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto no encontrado: " + icao));

        int[] thresholds = getThresholds();
        LocalDateTime now = effectiveNow();
        LocalDate agendaDate = (date == null || date.isBlank()) ? now.toLocalDate() : LocalDate.parse(date);
        LocalDateTime dayStart = agendaDate.atStartOfDay();
        LocalDateTime dayEnd = agendaDate.plusDays(1).atStartOfDay();
        long scheduledFlights = flightRepository.countScheduledDeparturesByOriginAndWindow(airport, dayStart, dayEnd);
        long inFlightFlights = flightRepository.countInFlightByAirportAtWithinDay(airport, now, dayStart, dayEnd);

        long storedShipments = shipmentRepository.countStoredByAirportWithinDay(airport, dayStart, dayEnd);
        long inbound = shipmentRepository.countInboundByAirportWithinDay(airport, dayStart, dayEnd);
        long outbound = shipmentRepository.countOutboundByAirportWithinDay(airport, dayStart, dayEnd);

        List<com.tasfb2b.dto.FlightScheduleEntryDto> nextFlights = new ArrayList<>();
        flightRepository.findUpcomingDeparturesByOriginAndWindow(airport, dayStart, dayEnd)
                .stream()
                .sorted(Comparator.comparing(Flight::getScheduledDeparture))
                .limit(12)
                .forEach(flight -> nextFlights.add(new com.tasfb2b.dto.FlightScheduleEntryDto(
                        flight.getFlightCode(),
                        flight.getOriginAirport().getIcaoCode(),
                        flight.getDestinationAirport().getIcaoCode(),
                        flight.getScheduledDeparture(),
                        flight.getScheduledArrival(),
                        flight.getMaxCapacity(),
                        flight.getCurrentLoad(),
                        flight.getAvailableCapacity(),
                        Boolean.TRUE.equals(flight.getIsInterContinental())
                )));

        NodeDetailDto response = new NodeDetailDto(
                airport.getId(),
                airport.getIcaoCode(),
                airport.getCity(),
                airport.getCountry(),
                airport.getContinent().name(),
                airport.getMaxStorageCapacity(),
                airport.getCurrentStorageLoad(),
                airport.getAvailableCapacity(),
                airport.getOccupancyPct(),
                airport.getStatus(thresholds[0], thresholds[1]),
                scheduledFlights,
                inFlightFlights,
                storedShipments,
                inbound,
                outbound,
                nextFlights
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/routes-network")
    @Operation(summary = "Red de rutas global con estado operativo/suspendido")
    public ResponseEntity<List<RouteNetworkEdgeDto>> getRoutesNetwork() {
        List<RouteNetworkEdgeDto> response = flightRepository.aggregateRouteNetwork().stream()
                .map(row -> {
                    long scheduledCount = row.getScheduledCount() == null ? 0L : row.getScheduledCount();
                    long inFlightCount = row.getInFlightCount() == null ? 0L : row.getInFlightCount();
                    long cancelledCount = row.getCancelledCount() == null ? 0L : row.getCancelledCount();
                    boolean operational = scheduledCount > 0 || inFlightCount > 0;
                    boolean suspended = !operational && cancelledCount > 0;
                    return new RouteNetworkEdgeDto(
                            row.getOriginIcao(),
                            row.getOriginLatitude(),
                            row.getOriginLongitude(),
                            row.getDestinationIcao(),
                            row.getDestinationLatitude(),
                            row.getDestinationLongitude(),
                            operational,
                            suspended,
                            scheduledCount,
                            inFlightCount,
                            cancelledCount
                    );
                })
                .toList();
        return ResponseEntity.ok(response);
    }

    private ShipmentSummaryDto toSummaryDto(Shipment shipment, LocalDateTime now) {
        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        Position current = computeCurrentPosition(shipment, stops, now);
        String lastVisited = stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.COMPLETED)
                .max(Comparator.comparingInt(TravelStop::getStopOrder))
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse(shipment.getOriginAirport().getIcaoCode());

        return new ShipmentSummaryDto(
                shipment.getId(),
                shipment.getShipmentCode(),
                shipment.getAirlineName(),
                shipment.getOriginAirport().getIcaoCode(),
                shipment.getOriginAirport().getLatitude(),
                shipment.getOriginAirport().getLongitude(),
                shipment.getDestinationAirport().getIcaoCode(),
                shipment.getDestinationAirport().getLatitude(),
                shipment.getDestinationAirport().getLongitude(),
                shipment.getStatus(),
                lastVisited,
                current.latitude(),
                current.longitude(),
                computeRemainingTime(shipment.getDeadline(), now),
                shipment.getProgressPercentage(),
                isAtRisk(shipment, now),
                shipment.getDeadline() != null && now.isAfter(shipment.getDeadline()) && shipment.getStatus() != ShipmentStatus.DELIVERED,
                computeCriticalReason(shipment)
        );
    }

    private ShipmentSummaryDto toSummaryDtoFast(DashboardShipmentCacheService.ShipmentSnapshotRow shipment, LocalDateTime now) {
        Position current;
        if (shipment.status() == ShipmentStatus.DELIVERED) {
            current = new Position(
                    shipment.destinationLatitude(),
                    shipment.destinationLongitude(),
                    shipment.destinationIcao()
            );
        } else {
            current = new Position(
                    shipment.originLatitude(),
                    shipment.originLongitude(),
                    shipment.originIcao()
            );
        }

        String remaining = computeRemainingTime(shipment.deadline(), now);
        boolean overdue = shipment.deadline() != null && now.isAfter(shipment.deadline()) && shipment.status() != ShipmentStatus.DELIVERED;
        boolean atRisk = overdue || shipment.status() == ShipmentStatus.CRITICAL || shipment.status() == ShipmentStatus.DELAYED;

        return new ShipmentSummaryDto(
                shipment.id(),
                shipment.shipmentCode(),
                shipment.airlineName(),
                shipment.originIcao(),
                shipment.originLatitude(),
                shipment.originLongitude(),
                shipment.destinationIcao(),
                shipment.destinationLatitude(),
                shipment.destinationLongitude(),
                shipment.status(),
                shipment.originIcao(),
                current.latitude(),
                current.longitude(),
                remaining,
                shipment.progressPercentage() == null ? 0.0 : shipment.progressPercentage(),
                atRisk,
                overdue,
                overdue ? "Deadline vencido" : null
        );
    }

    private Optional<Airport> currentAirport(List<TravelStop> stops) {
        return stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.IN_TRANSIT)
                .findFirst()
                .map(TravelStop::getAirport)
                .or(() -> stops.stream()
                        .filter(stop -> stop.getStopStatus() == StopStatus.COMPLETED)
                        .max(Comparator.comparingInt(TravelStop::getStopOrder))
                        .map(TravelStop::getAirport));
    }

    private Position computeCurrentPosition(Shipment shipment, List<TravelStop> stops, LocalDateTime now) {
        if (stops.isEmpty()) {
            return new Position(
                    shipment.getOriginAirport().getLatitude(),
                    shipment.getOriginAirport().getLongitude(),
                    shipment.getOriginAirport().getIcaoCode()
            );
        }

        for (TravelStop stop : stops) {
            if (stop.getStopStatus() != StopStatus.IN_TRANSIT) continue;

            Airport destination = stop.getAirport();
            Airport previous = previousAirport(stops, stop.getStopOrder())
                    .orElse(shipment.getOriginAirport());

            if (stop.getFlight() != null
                    && stop.getFlight().getScheduledDeparture() != null
                    && stop.getFlight().getScheduledArrival() != null) {
                long total = Math.max(1L, Duration.between(
                        stop.getFlight().getScheduledDeparture(),
                        stop.getFlight().getScheduledArrival()
                ).toSeconds());
                long elapsed = Duration.between(stop.getFlight().getScheduledDeparture(), now).toSeconds();
                double ratio = Math.max(0.0, Math.min(1.0, elapsed / (double) total));

                double fromLon = normalizeLongitude(previous.getLongitude());
                double toLon = nearestWrappedLongitude(normalizeLongitude(destination.getLongitude()), fromLon);
                double lon = normalizeLongitude(fromLon + (toLon - fromLon) * ratio);

                double fromMercY = mercatorY(previous.getLatitude());
                double toMercY = mercatorY(destination.getLatitude());
                double lat = inverseMercatorY(fromMercY + (toMercY - fromMercY) * ratio);
                return new Position(lat, lon, destination.getIcaoCode());
            }

            return new Position(destination.getLatitude(), destination.getLongitude(), destination.getIcaoCode());
        }

        return currentAirport(stops)
                .map(airport -> new Position(airport.getLatitude(), airport.getLongitude(), airport.getIcaoCode()))
                .orElseGet(() -> new Position(
                        shipment.getOriginAirport().getLatitude(),
                        shipment.getOriginAirport().getLongitude(),
                        shipment.getOriginAirport().getIcaoCode()
                ));
    }

    private Optional<Airport> previousAirport(List<TravelStop> stops, int currentOrder) {
        return stops.stream()
                .filter(stop -> stop.getStopOrder() < currentOrder)
                .max(Comparator.comparingInt(TravelStop::getStopOrder))
                .map(TravelStop::getAirport);
    }

    private double normalizeLongitude(double lon) {
        double value = lon;
        while (value > 180.0) value -= 360.0;
        while (value < -180.0) value += 360.0;
        return value;
    }

    private double nearestWrappedLongitude(double target, double reference) {
        double best = target;
        double bestDistance = Math.abs(target - reference);
        double[] shifts = new double[]{-720.0, -360.0, 0.0, 360.0, 720.0};
        for (double shift : shifts) {
            double candidate = target + shift;
            double distance = Math.abs(candidate - reference);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double mercatorY(double lat) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double radians = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI / 4.0 + radians / 2.0));
    }

    private double inverseMercatorY(double y) {
        return Math.toDegrees(2.0 * Math.atan(Math.exp(y)) - Math.PI / 2.0);
    }

    private record Position(double latitude, double longitude, String node) {
    }

    private String computeRemainingTime(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) return "N/A";
        Duration duration = Duration.between(now, deadline);
        long hours = duration.toHours();
        long minutes = Math.abs(duration.toMinutesPart());

        if (hours < 0) {
            return "Vencido";
        }
        return String.format("%dh %02dm", hours, minutes);
    }

    private String computeCriticalReason(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.CRITICAL && shipment.getStatus() != ShipmentStatus.DELAYED) {
            return null;
        }

        return shipmentAuditLogRepository.findTopByShipmentOrderByEventAtDesc(shipment)
                .map(log -> {
                    String message = log.getMessage() == null ? "Sin detalle" : log.getMessage().trim();
                    return message.isEmpty() ? "Sin detalle" : message;
                })
                .orElse("Sin detalle de auditoria");
    }

    private LocalDateTime effectiveNow() {
        return simulationRuntimeService.currentSimulationTime().orElse(LocalDateTime.now());
    }

    private LocalDateTime operationalDayStart(LocalDateTime now) {
        SimulationConfig config = currentConfig();
        LocalDateTime configuredStart = config == null ? null : config.getEffectiveScenarioStartAt();

        if (configuredStart == null) {
            return now.toLocalDate().atStartOfDay();
        }

        LocalDateTime candidate = configuredStart.toLocalDate().atStartOfDay();
        while (!candidate.plusDays(1).isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private SimulationConfig currentConfig() {
        return configRepository.findTopByOrderByIdAsc();
    }

    private boolean isPeriodSimulation(SimulationConfig config) {
        return config != null && config.getScenario() == SimulationScenario.PERIOD_SIMULATION;
    }

    private LocalDateTime activeVisibilityStart(SimulationConfig config, LocalDateTime fallbackNow) {
        if (!isPeriodSimulation(config)) {
            return operationalDayStart(fallbackNow);
        }
        if (config.getEffectiveScenarioStartAt() != null) {
            return config.getEffectiveScenarioStartAt();
        }
        if (config.getScenarioStartAt() != null) {
            return config.getScenarioStartAt();
        }
        return operationalDayStart(fallbackNow);
    }

    private boolean isAtRisk(Shipment shipment, LocalDateTime now) {
        if (shipment.getStatus() == ShipmentStatus.DELIVERED) return false;
        if (shipment.getDeadline() != null && shipment.getDeadline().isBefore(now)) return true;

        List<TravelStop> stops = travelStopRepository.findByShipment(shipment);
        return stops.stream()
                .filter(stop -> stop.getScheduledArrival() != null)
                .anyMatch(stop -> shipment.getDeadline() != null && stop.getScheduledArrival().isAfter(shipment.getDeadline()));
    }

    private int[] getThresholds() {
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
        return config == null ? new int[]{70, 90} : thresholdsFromConfig(config);
    }

    private int[] thresholdsFromConfig(SimulationConfig config) {
        return new int[]{config.getNormalThresholdPct(), config.getWarningThresholdPct()};
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
}
