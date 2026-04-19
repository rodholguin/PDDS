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
import com.tasfb2b.service.algorithm.SimulatedAnnealingOptimization;
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
    @Mock private SimulatedAnnealingOptimization simulatedAnnealingOptimization;
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
        origin = new Airport();
        origin.setId(1L);
        origin.setIcaoCode("SKBO");
        origin.setContinent(Continent.AMERICA);
        origin.setCity("Bogota");
        origin.setCountry("Colombia");
        origin.setLatitude(4.7016);
        origin.setLongitude(-74.1469);
        origin.setMaxStorageCapacity(800);
        origin.setCurrentStorageLoad(0);

        destination = new Airport();
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
        shipment.setId(10L);
        shipment.setShipmentCode("TEST-1");
        shipment.setOriginAirport(origin);
        shipment.setDestinationAirport(destination);
        shipment.setLuggageCount(10);
        shipment.setRegistrationDate(LocalDateTime.now().minusMinutes(5));
        shipment.setStatus(ShipmentStatus.PENDING);
        shipment.setProgressPercentage(0.0);

        flight = new Flight();
        flight.setId(99L);
        flight.setFlightCode("SKBOSEQM1");
        flight.setOriginAirport(origin);
        flight.setDestinationAirport(destination);
        flight.setScheduledDeparture(LocalDateTime.now().plusHours(1));
        flight.setScheduledArrival(LocalDateTime.now().plusHours(2));
        flight.setMaxCapacity(50);
        flight.setCurrentLoad(0);
        flight.setStatus(FlightStatus.SCHEDULED);
    }

    @Test
    void planShipmentStartsAtOriginWithZeroProgress() {
        TravelStop originStop = new TravelStop();
        originStop.setAirport(origin);
        originStop.setFlight(null);
        originStop.setStopOrder(0);
        originStop.setStopStatus(StopStatus.PENDING);
        originStop.setScheduledArrival(shipment.getRegistrationDate());

        TravelStop destinationStop = new TravelStop();
        destinationStop.setAirport(destination);
        destinationStop.setFlight(flight);
        destinationStop.setStopOrder(1);
        destinationStop.setStopStatus(StopStatus.PENDING);
        destinationStop.setScheduledArrival(flight.getScheduledArrival());

        when(antColonyOptimization.getAlgorithmName()).thenReturn("Ant Colony Optimization");
        when(simulatedAnnealingOptimization.getAlgorithmName()).thenReturn("Simulated Annealing");
        when(geneticAlgorithm.planRoute(any(), anyList(), anyList())).thenReturn(List.of(originStop, destinationStop));
        SimulationConfig config = new SimulationConfig();
        config.setNormalThresholdPct(70);
        config.setWarningThresholdPct(90);
        when(configRepository.findTopByOrderByIdAsc()).thenReturn(config);
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
