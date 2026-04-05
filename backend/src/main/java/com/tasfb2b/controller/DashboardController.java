package com.tasfb2b.controller;

import com.tasfb2b.dto.DashboardKpisDto;
import com.tasfb2b.dto.DashboardOverviewDto;
import com.tasfb2b.dto.NodeDetailDto;
import com.tasfb2b.dto.RouteNetworkEdgeDto;
import com.tasfb2b.dto.ShipmentSearchResultDto;
import com.tasfb2b.dto.ShipmentSummaryDto;
import com.tasfb2b.dto.SystemStatusDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.AirportStatus;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.OperationalAlertRepository;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.RoutePlannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "KPIs y estado global del sistema para el mapa")
public class DashboardController {

    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final SimulationConfigRepository configRepository;
    private final TravelStopRepository travelStopRepository;
    private final RoutePlannerService routePlannerService;
    private final CollapseMonitorService collapseMonitorService;
    private final OperationalAlertRepository operationalAlertRepository;
    private final ShipmentAuditLogRepository shipmentAuditLogRepository;

    @GetMapping("/kpis")
    @Operation(summary = "KPIs principales para la cabecera del dashboard")
    public ResponseEntity<DashboardKpisDto> getKpis() {
        int[] thresholds = getThresholds();
        long totalShipments = shipmentRepository.count();
        long activeShipments = shipmentRepository.findActiveShipments().size();
        long criticalShipments = shipmentRepository.findCriticalShipments().size();
        long delivered = shipmentRepository.countDeliveredTotal();

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

        long totalFlights = flightRepository.count();
        long scheduledFlights = flightRepository.countByStatus(FlightStatus.SCHEDULED);
        long inFlight = flightRepository.countByStatus(FlightStatus.IN_FLIGHT);

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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime tomorrow = todayStart.plusDays(1);
        LocalDateTime yesterdayStart = todayStart.minusDays(1);

        long activeFlights = flightRepository.countActiveFlightsAt(now);
        long inRoute = shipmentRepository.countInRoute();
        long totalToday = shipmentRepository.countByRegistrationDateBetween(todayStart, tomorrow);

        long deliveredToday = shipmentRepository.countDeliveredBetween(todayStart, tomorrow);
        long deliveredOnTimeToday = shipmentRepository.countDeliveredOnTimeBetween(todayStart, tomorrow);
        long deliveredYesterday = shipmentRepository.countDeliveredBetween(yesterdayStart, todayStart);
        long deliveredOnTimeYesterday = shipmentRepository.countDeliveredOnTimeBetween(yesterdayStart, todayStart);

        double slaToday = deliveredToday == 0 ? 0.0 : (deliveredOnTimeToday * 100.0) / deliveredToday;
        double slaYesterday = deliveredYesterday == 0 ? 0.0 : (deliveredOnTimeYesterday * 100.0) / deliveredYesterday;
        double delta = deliveredYesterday == 0
                ? (slaToday > 0 ? 100.0 : 0.0)
                : ((slaToday - slaYesterday) / Math.max(0.0001, slaYesterday)) * 100.0;

        long unresolved = operationalAlertRepository.countByStatusIn(List.of(
                com.tasfb2b.model.OperationalAlertStatus.PENDING,
                com.tasfb2b.model.OperationalAlertStatus.IN_REVIEW
        ));
        long overdue = shipmentRepository.findOverdueShipments(now).size();
        long atRisk = shipmentRepository.findAtRiskShipments(now).size();
        long stalled = shipmentRepository.findShipmentsWithoutMovement(now.minusHours(6)).size();

        long intra = shipmentRepository.countActiveByRouteType(false);
        long inter = shipmentRepository.countActiveByRouteType(true);
        long totalActive = intra + inter;
        double intraPct = totalActive == 0 ? 0.0 : (intra * 100.0) / totalActive;
        double interPct = totalActive == 0 ? 0.0 : (inter * 100.0) / totalActive;

        List<Airport> airports = airportRepository.findAll();
        long availableNodes = airports.stream().filter(airport -> airport.getOccupancyPct() < 90.0).count();
        double availableNodesPct = airports.isEmpty() ? 0.0 : (availableNodes * 100.0) / airports.size();

        List<Shipment> deliveredPeriod = shipmentRepository.findAll().stream()
                .filter(shipment -> shipment.getStatus() == ShipmentStatus.DELIVERED)
                .filter(shipment -> shipment.getDeliveredAt() != null && shipment.getRegistrationDate() != null)
                .filter(shipment -> !shipment.getDeliveredAt().isBefore(todayStart) && shipment.getDeliveredAt().isBefore(tomorrow))
                .toList();
        double avgDeliveryHours = deliveredPeriod.stream()
                .mapToDouble(shipment -> Duration.between(shipment.getRegistrationDate(), shipment.getDeliveredAt()).toHours())
                .average()
                .orElse(0.0);
        double avgCommittedHours = deliveredPeriod.stream()
                .mapToDouble(shipment -> Duration.between(shipment.getRegistrationDate(), shipment.getDeadline()).toHours())
                .average()
                .orElse(0.0);
        double avgDeliveryDeltaHours = avgDeliveryHours - avgCommittedHours;

        long replanningsToday = shipmentAuditLogRepository.countByEventTypeAndPeriod(
                com.tasfb2b.model.ShipmentAuditType.ROUTE_REPLANNED,
                todayStart,
                tomorrow
        );

        return ResponseEntity.ok(new DashboardOverviewDto(
                activeFlights,
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
    public ResponseEntity<List<ShipmentSummaryDto>> getShipmentSummaries(
            @RequestParam(required = false) String airline,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) ShipmentStatus status
    ) {
        LocalDateTime now = LocalDateTime.now();
        List<Shipment> shipments = shipmentRepository.searchShipments(
                normalizeUpper(airline),
                normalizeUpper(origin),
                normalizeUpper(destination),
                status
        );

        List<ShipmentSummaryDto> response = shipments.stream()
                .map(shipment -> toSummaryDto(shipment, now))
                .toList();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/shipments/search")
    @Operation(summary = "Busqueda de envio por identificador unico")
    public ResponseEntity<ShipmentSearchResultDto> searchShipmentByCode(@RequestParam String code) {
        Shipment shipment = shipmentRepository.findByShipmentCode(code.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Envio no encontrado: " + code));

        List<TravelStop> stops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
        LocalDateTime now = LocalDateTime.now();
        Position current = computeCurrentPosition(shipment, stops, now);
        String lastVisited = stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.COMPLETED)
                .max(Comparator.comparingInt(TravelStop::getStopOrder))
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse("N/A");

        String currentNode = stops.stream()
                .filter(stop -> stop.getStopStatus() == StopStatus.IN_TRANSIT)
                .findFirst()
                .map(stop -> stop.getAirport().getIcaoCode())
                .orElse(lastVisited);

        String remaining = computeRemainingTime(shipment.getDeadline());
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
        LocalDateTime now = LocalDateTime.now();
        LocalDate agendaDate = (date == null || date.isBlank()) ? now.toLocalDate() : LocalDate.parse(date);
        LocalDateTime dayStart = agendaDate.atStartOfDay();
        LocalDateTime dayEnd = agendaDate.plusDays(1).atStartOfDay();
        List<Flight> allFlights = flightRepository.findByAirport(airport);

        long scheduledFlights = allFlights.stream().filter(flight -> flight.getStatus() == FlightStatus.SCHEDULED).count();
        long inFlightFlights = allFlights.stream().filter(flight -> flight.getStatus() == FlightStatus.IN_FLIGHT).count();

        List<Shipment> nodeShipments = shipmentRepository.findByOriginAirportOrDestinationAirport(airport, airport);
        long storedShipments = nodeShipments.stream().filter(shipment -> shipment.getStatus() != ShipmentStatus.DELIVERED).count();
        long inbound = nodeShipments.stream().filter(shipment -> shipment.getDestinationAirport().getId().equals(airport.getId())).count();
        long outbound = nodeShipments.stream().filter(shipment -> shipment.getOriginAirport().getId().equals(airport.getId())).count();

        List<com.tasfb2b.dto.FlightScheduleEntryDto> nextFlights = new ArrayList<>();
        flightRepository.findByOriginAirportAndScheduledDepartureBetween(airport, dayStart, dayEnd)
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
        List<Flight> flights = flightRepository.findAll();
        Map<String, RouteAggregate> buckets = new HashMap<>();

        for (Flight flight : flights) {
            String key = flight.getOriginAirport().getIcaoCode() + "->" + flight.getDestinationAirport().getIcaoCode();
            RouteAggregate aggregate = buckets.computeIfAbsent(key, ignored -> new RouteAggregate(
                    flight.getOriginAirport().getIcaoCode(),
                    flight.getOriginAirport().getLatitude(),
                    flight.getOriginAirport().getLongitude(),
                    flight.getDestinationAirport().getIcaoCode(),
                    flight.getDestinationAirport().getLatitude(),
                    flight.getDestinationAirport().getLongitude()
            ));
            aggregate.consume(flight.getStatus());
        }

        List<RouteNetworkEdgeDto> response = buckets.values().stream()
                .map(RouteAggregate::toDto)
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
                .orElse("N/A");

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
                computeRemainingTime(shipment.getDeadline()),
                shipment.getProgressPercentage(),
                isAtRisk(shipment, now),
                shipment.isOverdue()
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

    private static final class RouteAggregate {
        private final String originIcao;
        private final Double originLatitude;
        private final Double originLongitude;
        private final String destinationIcao;
        private final Double destinationLatitude;
        private final Double destinationLongitude;

        private long scheduledCount;
        private long inFlightCount;
        private long cancelledCount;

        private RouteAggregate(String originIcao,
                               Double originLatitude,
                               Double originLongitude,
                               String destinationIcao,
                               Double destinationLatitude,
                               Double destinationLongitude) {
            this.originIcao = originIcao;
            this.originLatitude = originLatitude;
            this.originLongitude = originLongitude;
            this.destinationIcao = destinationIcao;
            this.destinationLatitude = destinationLatitude;
            this.destinationLongitude = destinationLongitude;
        }

        private void consume(FlightStatus status) {
            if (status == FlightStatus.SCHEDULED) scheduledCount++;
            if (status == FlightStatus.IN_FLIGHT) inFlightCount++;
            if (status == FlightStatus.CANCELLED) cancelledCount++;
        }

        private RouteNetworkEdgeDto toDto() {
            boolean operational = scheduledCount > 0 || inFlightCount > 0;
            boolean suspended = !operational && cancelledCount > 0;
            return new RouteNetworkEdgeDto(
                    originIcao,
                    originLatitude,
                    originLongitude,
                    destinationIcao,
                    destinationLatitude,
                    destinationLongitude,
                    operational,
                    suspended,
                    scheduledCount,
                    inFlightCount,
                    cancelledCount
            );
        }
    }

    private String computeRemainingTime(LocalDateTime deadline) {
        if (deadline == null) return "N/A";
        Duration duration = Duration.between(LocalDateTime.now(), deadline);
        long hours = duration.toHours();
        long minutes = Math.abs(duration.toMinutesPart());

        if (hours < 0) {
            return "Vencido";
        }
        return String.format("%dh %02dm", hours, minutes);
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
        return configRepository.findAll().stream()
                .findFirst()
                .map(this::thresholdsFromConfig)
                .orElse(new int[]{70, 90});
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
