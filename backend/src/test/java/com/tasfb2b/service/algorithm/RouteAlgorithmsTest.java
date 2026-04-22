package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.TravelStop;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteAlgorithmsTest {

    @Test
    void geneticAlgorithmFindsFeasibleRoute() {
        TestScenario scenario = new TestScenario();
        GeneticAlgorithm ga = new GeneticAlgorithm();
        ga.setPopulationSize(24);
        ga.setGenerations(12);
        ga.setMutationRate(0.12);

        List<TravelStop> route = ga.planRoute(scenario.shipment, scenario.flights, scenario.airports);

        assertFalse(route.isEmpty());
        assertEquals(scenario.destination.getId(), route.get(route.size() - 1).getAirport().getId());
    }

    @Test
    void antColonyFindsFeasibleRoute() {
        TestScenario scenario = new TestScenario();
        AntColonyOptimization aco = new AntColonyOptimization();
        aco.setNumAnts(20);
        aco.setIterations(12);

        List<TravelStop> route = aco.planRoute(scenario.shipment, scenario.flights, scenario.airports);

        assertFalse(route.isEmpty());
        assertEquals(scenario.destination.getId(), route.get(route.size() - 1).getAirport().getId());
    }

    @Test
    void sharedSupportRejectsBrokenConnections() {
        TestScenario scenario = new TestScenario();
        List<Flight> broken = List.of(scenario.firstLeg, scenario.invalidSecondLeg);

        assertFalse(RoutePlanningSupport.isFeasibleRoute(scenario.shipment, broken));
        assertTrue(RoutePlanningSupport.isFeasibleRoute(scenario.shipment, List.of(scenario.firstLeg, scenario.secondLeg)));
    }

    private static final class TestScenario {
        final Airport origin;
        final Airport hub;
        final Airport destination;
        final Shipment shipment;
        final Flight firstLeg;
        final Flight secondLeg;
        final Flight invalidSecondLeg;
        final List<Flight> flights;
        final List<Airport> airports;

        TestScenario() {
            origin = airport(1L, "SKBO", Continent.AMERICA, 20.0);
            hub = airport(2L, "SEQM", Continent.AMERICA, 32.0);
            destination = airport(3L, "SABE", Continent.AMERICA, 28.0);

            LocalDateTime registration = LocalDateTime.of(2028, 4, 15, 0, 0);
            shipment = Shipment.builder()
                    .shipmentCode("TEST-ROUTE-1")
                    .airlineName("LATAM")
                    .originAirport(origin)
                    .destinationAirport(destination)
                    .luggageCount(12)
                    .registrationDate(registration)
                    .deadline(registration.plusDays(1))
                    .build();

            firstLeg = flight(100L, "GA100", origin, hub, registration.plusHours(2), registration.plusHours(4), 120, 20);
            secondLeg = flight(101L, "GA101", hub, destination, registration.plusHours(5), registration.plusHours(7), 120, 22);
            invalidSecondLeg = flight(102L, "GA102", hub, destination, registration.plusHours(3), registration.plusHours(4), 120, 22);
            Flight directButLoaded = flight(103L, "GA103", origin, destination, registration.plusHours(6), registration.plusHours(9), 120, 110);

            flights = List.of(firstLeg, secondLeg, invalidSecondLeg, directButLoaded);
            airports = List.of(origin, hub, destination);
        }

        private Airport airport(Long id, String icao, Continent continent, double occupancyPct) {
            Airport airport = new Airport();
            airport.setId(id);
            airport.setIcaoCode(icao);
            airport.setCity(icao);
            airport.setCountry("X");
            airport.setLatitude(0.0);
            airport.setLongitude(0.0);
            airport.setContinent(continent);
            airport.setMaxStorageCapacity(1000);
            airport.setCurrentStorageLoad((int) Math.round(occupancyPct * 10));
            return airport;
        }

        private Flight flight(Long id,
                              String code,
                              Airport originAirport,
                              Airport destinationAirport,
                              LocalDateTime departure,
                              LocalDateTime arrival,
                              int maxCapacity,
                              int currentLoad) {
            Flight flight = new Flight();
            flight.setId(id);
            flight.setFlightCode(code);
            flight.setOriginAirport(originAirport);
            flight.setDestinationAirport(destinationAirport);
            flight.setScheduledDeparture(departure);
            flight.setScheduledArrival(arrival);
            flight.setMaxCapacity(maxCapacity);
            flight.setCurrentLoad(currentLoad);
            flight.setStatus(FlightStatus.SCHEDULED);
            return flight;
        }
    }
}
