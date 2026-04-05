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

    @InjectMocks
    private SimulationEngineService simulationEngineService;

    private Shipment shipment;
    private TravelStop originStop;
    private TravelStop destinationStop;
    private Flight flight;

    @BeforeEach
    void setUp() {
        Airport origin = Airport.builder().id(1L).icaoCode("SKBO").continent(Continent.AMERICA).build();
        Airport destination = Airport.builder().id(2L).icaoCode("SEQM").continent(Continent.AMERICA).build();

        shipment = Shipment.builder()
                .id(1L)
                .shipmentCode("000000001-20250102")
                .originAirport(origin)
                .destinationAirport(destination)
                .luggageCount(15)
                .status(ShipmentStatus.PENDING)
                .registrationDate(LocalDateTime.now().minusMinutes(2))
                .build();

        flight = Flight.builder()
                .id(100L)
                .flightCode("FL100")
                .originAirport(origin)
                .destinationAirport(destination)
                .scheduledDeparture(LocalDateTime.now().minusMinutes(1))
                .scheduledArrival(LocalDateTime.now().plusHours(1))
                .maxCapacity(100)
                .currentLoad(20)
                .status(FlightStatus.SCHEDULED)
                .build();

        originStop = TravelStop.builder()
                .shipment(shipment)
                .airport(origin)
                .flight(null)
                .stopOrder(0)
                .stopStatus(StopStatus.PENDING)
                .build();

        destinationStop = TravelStop.builder()
                .shipment(shipment)
                .airport(destination)
                .flight(flight)
                .stopOrder(1)
                .stopStatus(StopStatus.PENDING)
                .build();
    }

    @Test
    void tickMovesShipmentFromOriginToInTransitOnlyAtDeparture() {
        SimulationConfig config = SimulationConfig.builder().isRunning(true).build();
        when(simulationConfigRepository.findTopByOrderByIdAsc()).thenReturn(config);
        when(runtimeService.isPaused()).thenReturn(false);
        when(runtimeService.currentSpeed()).thenReturn(1);
        when(runtimeService.currentSimulationTime()).thenReturn(java.util.Optional.of(LocalDateTime.now().minusMinutes(3)));

        when(flightRepository.findByStatusAndScheduledDepartureLessThanEqual(any(), any())).thenReturn(List.of(flight));
        when(travelStopRepository.findByFlightAndStopStatus(flight, StopStatus.PENDING)).thenReturn(List.of(destinationStop));
        when(travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment)).thenReturn(List.of(originStop, destinationStop));
        when(shipmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(travelStopRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(flightRepository.findByStatusAndScheduledArrivalLessThanEqual(any(), any())).thenReturn(List.of());
        when(shipmentRepository.findActiveShipments()).thenReturn(List.of(shipment));

        simulationEngineService.tick();

        assertEquals(StopStatus.COMPLETED, originStop.getStopStatus());
        assertEquals(StopStatus.IN_TRANSIT, destinationStop.getStopStatus());
        assertEquals(ShipmentStatus.IN_ROUTE, shipment.getStatus());
    }
}
