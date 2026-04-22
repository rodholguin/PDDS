package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationEngineDepartureFlowTest {

    @Mock private SimulationConfigRepository simulationConfigRepository;
    @Mock private FlightRepository flightRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private TravelStopRepository travelStopRepository;
    @Mock private AirportRepository airportRepository;
    @Mock private ShipmentAuditService shipmentAuditService;
    @Mock private SimulationRuntimeService runtimeService;
    @Mock private RoutePlannerService routePlannerService;
    @Mock private FlightScheduleService flightScheduleService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private SimulationEngineService simulationEngineService;

    private Shipment shipment;
    private TravelStop originStop;
    private TravelStop destinationStop;
    private Flight flight;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        Airport origin = new Airport();
        origin.setId(1L);
        origin.setIcaoCode("SKBO");
        origin.setContinent(Continent.AMERICA);
        origin.setCity("Bogota");
        origin.setCountry("Colombia");
        origin.setLatitude(4.7016);
        origin.setLongitude(-74.1469);
        origin.setMaxStorageCapacity(800);
        origin.setCurrentStorageLoad(0);

        Airport destination = new Airport();
        destination.setId(2L);
        destination.setIcaoCode("SEQM");
        destination.setContinent(Continent.AMERICA);
        destination.setCity("Quito");
        destination.setCountry("Ecuador");
        destination.setLatitude(-0.1292);
        destination.setLongitude(-78.3575);
        destination.setMaxStorageCapacity(800);
        destination.setCurrentStorageLoad(0);

        shipment = new Shipment();
        shipment.setId(1L);
        shipment.setShipmentCode("000000001-20250102");
        shipment.setOriginAirport(origin);
        shipment.setDestinationAirport(destination);
        shipment.setLuggageCount(15);
        shipment.setStatus(ShipmentStatus.PENDING);
        shipment.setRegistrationDate(LocalDateTime.now().minusMinutes(2));

        flight = new Flight();
        flight.setId(100L);
        flight.setFlightCode("FL100");
        flight.setOriginAirport(origin);
        flight.setDestinationAirport(destination);
        flight.setScheduledDeparture(LocalDateTime.now().minusMinutes(1));
        flight.setScheduledArrival(LocalDateTime.now().plusHours(1));
        flight.setMaxCapacity(100);
        flight.setCurrentLoad(20);
        flight.setStatus(FlightStatus.SCHEDULED);

        originStop = new TravelStop();
        originStop.setShipment(shipment);
        originStop.setAirport(origin);
        originStop.setFlight(null);
        originStop.setStopOrder(0);
        originStop.setStopStatus(StopStatus.PENDING);

        destinationStop = new TravelStop();
        destinationStop.setShipment(shipment);
        destinationStop.setAirport(destination);
        destinationStop.setFlight(flight);
        destinationStop.setStopOrder(1);
        destinationStop.setStopStatus(StopStatus.PENDING);
    }

    @Test
    void tickMovesShipmentFromOriginToInTransitOnlyAtDeparture() {
        SimulationConfig config = new SimulationConfig();
        config.setIsRunning(true);
        when(simulationConfigRepository.findTopByOrderByIdAsc()).thenReturn(config);
        when(runtimeService.isPaused()).thenReturn(false);
        when(runtimeService.currentSimulationTime()).thenReturn(java.util.Optional.of(LocalDateTime.now().minusMinutes(3)));

        when(flightRepository.findByStatusAndScheduledDepartureLessThanEqual(any(), any())).thenReturn(List.of(flight));
        when(travelStopRepository.findByFlightInAndStopStatus(List.of(flight), StopStatus.PENDING)).thenReturn(List.of(destinationStop));
        when(travelStopRepository.findByShipmentInOrderByShipmentIdAscStopOrderAsc(List.of(shipment))).thenReturn(List.of(originStop, destinationStop));
        when(shipmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(travelStopRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(flightRepository.findByStatusAndScheduledArrivalLessThanEqual(any(), any())).thenReturn(List.of());
        simulationEngineService.tick();

        assertEquals(StopStatus.COMPLETED, originStop.getStopStatus());
        assertEquals(StopStatus.IN_TRANSIT, destinationStop.getStopStatus());
        assertEquals(ShipmentStatus.IN_ROUTE, shipment.getStatus());
    }
}
