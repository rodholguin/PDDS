package com.tasfb2b.service;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.repository.TravelStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationEngineService {

    private static final int BASE_SIM_MINUTES_PER_TICK = 2;

    private final SimulationConfigRepository simulationConfigRepository;
    private final FlightRepository flightRepository;
    private final ShipmentRepository shipmentRepository;
    private final TravelStopRepository travelStopRepository;
    private final AirportRepository airportRepository;
    private final ShipmentAuditService shipmentAuditService;
    private final SimulationRuntimeService runtimeService;

    @Scheduled(fixedDelay = 2_000)
    @Transactional
    public void tick() {
        SimulationConfig config = getConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning()) || runtimeService.isPaused()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int speed = Math.max(1, runtimeService.currentSpeed());
        LocalDateTime simulatedNow = runtimeService.currentSimulationTime().orElseGet(() -> resolveInitialSimulationTime(now));
        int minutesAdvance = Math.min(40, BASE_SIM_MINUTES_PER_TICK * speed);
        LocalDateTime horizon = simulatedNow.plusMinutes(minutesAdvance);
        runtimeService.setSimulationTime(horizon);

        activateFlights(horizon);
        closeFlightsAndAdvanceStops(horizon);
        markOverdueShipments(horizon);

        runtimeService.markTick(horizon);
    }

    private void activateFlights(LocalDateTime horizon) {
        List<Flight> toStart = flightRepository
                .findByStatusAndScheduledDepartureLessThanEqual(FlightStatus.SCHEDULED, horizon)
                .stream()
                .filter(flight -> flight.getCurrentLoad() > 0)
                .toList();

        for (Flight flight : toStart) {
            flight.setStatus(FlightStatus.IN_FLIGHT);
            flightRepository.save(flight);

            List<TravelStop> stops = travelStopRepository.findByFlightAndStopStatus(flight, StopStatus.PENDING);

            for (TravelStop stop : stops) {
                List<TravelStop> allStops = travelStopRepository.findByShipmentOrderByStopOrderAsc(stop.getShipment());
                allStops.stream()
                        .filter(s -> s.getStopOrder() == 0 && s.getStopStatus() == StopStatus.PENDING)
                        .forEach(originStop -> {
                            originStop.setStopStatus(StopStatus.COMPLETED);
                            originStop.setActualArrival(flight.getScheduledDeparture() != null ? flight.getScheduledDeparture() : horizon);
                            travelStopRepository.save(originStop);
                        });

                stop.setStopStatus(StopStatus.IN_TRANSIT);
                travelStopRepository.save(stop);

                Shipment shipment = stop.getShipment();
                shipment.setStatus(ShipmentStatus.IN_ROUTE);
                shipmentRepository.save(shipment);

                audit(shipment, ShipmentAuditType.DEPARTED,
                        "Vuelo " + flight.getFlightCode() + " en curso hacia " + stop.getAirport().getIcaoCode(),
                        stop.getAirport(), flight.getFlightCode());
            }
        }
    }

    private void closeFlightsAndAdvanceStops(LocalDateTime horizon) {
        List<Flight> toComplete = flightRepository
                .findByStatusAndScheduledArrivalLessThanEqual(FlightStatus.IN_FLIGHT, horizon);

        for (Flight flight : toComplete) {
            flight.setStatus(FlightStatus.COMPLETED);
            flightRepository.save(flight);

            List<TravelStop> impacted = travelStopRepository.findByFlightAndStopStatus(flight, StopStatus.IN_TRANSIT);

            for (TravelStop stop : impacted) {
                Shipment shipment = stop.getShipment();
                stop.setStopStatus(StopStatus.COMPLETED);
                stop.setActualArrival(horizon);
                travelStopRepository.save(stop);

                Airport airport = stop.getAirport();
                airport.setCurrentStorageLoad(Math.min(
                        airport.getMaxStorageCapacity(),
                        airport.getCurrentStorageLoad() + shipment.getLuggageCount()
                ));
                airportRepository.save(airport);

                audit(shipment, ShipmentAuditType.ARRIVED,
                        "Arribo a " + airport.getIcaoCode() + " mediante " + flight.getFlightCode(),
                        airport, flight.getFlightCode());

                List<TravelStop> allStops = travelStopRepository.findByShipmentOrderByStopOrderAsc(shipment);
                boolean allDone = allStops.stream().allMatch(s -> s.getStopStatus() == StopStatus.COMPLETED);
                if (allDone) {
                    shipment.setStatus(ShipmentStatus.DELIVERED);
                    shipment.setDeliveredAt(horizon);
                    shipment.setProgressPercentage(100.0);
                    shipmentRepository.save(shipment);
                    audit(shipment, ShipmentAuditType.DELIVERED,
                            "Envio entregado en destino", airport, flight.getFlightCode());
                } else {
                    double progress = allStops.isEmpty()
                            ? 0.0
                            : (allStops.stream().filter(s -> s.getStopStatus() == StopStatus.COMPLETED).count() * 100.0) / allStops.size();
                    shipment.setProgressPercentage(progress);
                    shipmentRepository.save(shipment);
                }
            }
        }
    }

    private void markOverdueShipments(LocalDateTime now) {
        List<Shipment> active = shipmentRepository.findActiveShipments();
        for (Shipment shipment : active) {
            if (shipment.getDeadline() != null && shipment.getDeadline().isBefore(now)) {
                shipment.setStatus(ShipmentStatus.DELAYED);
                shipmentRepository.save(shipment);
                audit(shipment, ShipmentAuditType.DELAYED,
                        "Envio supero su deadline operativo", shipment.getDestinationAirport(), null);
            }
        }
    }

    private void audit(Shipment shipment,
                       ShipmentAuditType type,
                       String message,
                       Airport airport,
                       String flightCode) {
        shipmentAuditService.log(shipment, type, message, airport, flightCode);
    }

    private SimulationConfig getConfig() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        return config != null
                ? config
                : simulationConfigRepository.save(SimulationConfig.builder().build());
    }

    private LocalDateTime resolveInitialSimulationTime(LocalDateTime fallbackNow) {
        return shipmentRepository.findAll().stream()
                .map(Shipment::getRegistrationDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(fallbackNow);
    }
}
