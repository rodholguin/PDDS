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
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlannerInitialStateTest {

    @Mock private GeneticAlgorithm geneticAlgorithm;
    @Mock private AntColonyOptimization antColonyOptimization;
    @Mock private AirportRepository airportRepository;
    @Mock private FlightRepository flightRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private TravelStopRepository travelStopRepository;
    @Mock private SimulationConfigRepository configRepository;
    @Mock private ShipmentAuditService shipmentAuditService;

    @InjectMocks
    private RoutePlannerService routePlannerService;

    private Shipment shipment;
    private Flight flight;
    private Airport origin;
    private Airport destination;

    @BeforeEach
    void setUp() {
        origin = Airport.builder().id(1L).icaoCode("SKBO").continent(Continent.AMERICA).build();
        destination = Airport.builder().id(2L).icaoCode("SEQM").continent(Continent.AMERICA).build();
        shipment = Shipment.builder()
                .id(10L)
                .originAirport(origin)
                .destinationAirport(destination)
                .luggageCount(10)
                .registrationDate(LocalDateTime.now().minusMinutes(5))
                .status(ShipmentStatus.PENDING)
                .progressPercentage(0.0)
                .build();

        flight = Flight.builder()
                .id(99L)
                .flightCode("SKBOSEQM1")
                .originAirport(origin)
                .destinationAirport(destination)
                .scheduledDeparture(LocalDateTime.now().plusHours(1))
                .scheduledArrival(LocalDateTime.now().plusHours(2))
                .maxCapacity(50)
                .currentLoad(0)
                .status(FlightStatus.SCHEDULED)
                .build();
    }

    @Test
    void planShipmentStartsAtOriginWithZeroProgress() {
        TravelStop originStop = TravelStop.builder()
                .airport(origin)
                .flight(null)
                .stopOrder(0)
                .stopStatus(StopStatus.PENDING)
                .scheduledArrival(shipment.getRegistrationDate())
                .build();
        TravelStop destinationStop = TravelStop.builder()
                .airport(destination)
                .flight(flight)
                .stopOrder(1)
                .stopStatus(StopStatus.PENDING)
                .scheduledArrival(flight.getScheduledArrival())
                .build();

        when(antColonyOptimization.getAlgorithmName()).thenReturn("Ant Colony Optimization");
        when(geneticAlgorithm.planRoute(any(), anyList(), anyList())).thenReturn(List.of(originStop, destinationStop));
        when(configRepository.findTopByOrderByIdAsc()).thenReturn(SimulationConfig.builder()
                .normalThresholdPct(70)
                .warningThresholdPct(90)
                .build());
        when(travelStopRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(shipmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TravelStop> planned = routePlannerService.planShipment(
                shipment,
                "Genetic Algorithm",
                List.of(flight),
                List.of(origin, destination),
                false
        );

        assertFalse(planned.isEmpty());
        assertEquals(StopStatus.PENDING, planned.get(0).getStopStatus());
        assertEquals(ShipmentStatus.PENDING, shipment.getStatus());
        assertEquals(0.0, shipment.getProgressPercentage());
    }
}
