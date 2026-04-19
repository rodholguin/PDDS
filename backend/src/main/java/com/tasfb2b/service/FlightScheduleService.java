package com.tasfb2b.service;

import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightScheduleService {

    private static final String RECURRENT_SUFFIX = "R";
    private final FlightRepository flightRepository;
    private final SimulationConfigRepository simulationConfigRepository;
    private final Map<String, LocalDateTime> ensuredWindows = new ConcurrentHashMap<>();

    @Transactional
    public List<Flight> ensureFlightsForWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            return List.of();
        }

        List<Flight> existing = flightRepository.findFlightsWithinWindow(fromInclusive, toExclusive);

        LocalDate earliestExistingDate = java.util.Optional.ofNullable(flightRepository.findEarliestScheduledDeparture())
                .map(LocalDateTime::toLocalDate)
                .orElse(null);

        if (earliestExistingDate == null) {
            return existing;
        }

        List<Flight> templates = flightRepository.findTemplateFlightsWithinWindow(
                        earliestExistingDate.atStartOfDay(),
                        earliestExistingDate.plusDays(1).atStartOfDay())
                .stream()
                .filter(f -> !f.getFlightCode().contains(RECURRENT_SUFFIX))
                .toList();

        if (templates.isEmpty()) {
            return existing;
        }

        Set<String> existingCodes = existing.stream()
                .map(Flight::getFlightCode)
                .collect(Collectors.toCollection(HashSet::new));

        Map<String, Flight> existingByNaturalKey = existing.stream()
                .filter(f -> f.getScheduledDeparture() != null)
                .collect(Collectors.toMap(this::naturalKey, f -> f, (first, second) -> first));

        List<Flight> generated = new ArrayList<>();
        LocalDate cursor = fromInclusive.toLocalDate();
        LocalDate endDate = toExclusive.minusNanos(1).toLocalDate();
        while (!cursor.isAfter(endDate)) {
            for (Flight template : templates) {
                LocalDateTime departure = LocalDateTime.of(cursor, template.getScheduledDeparture().toLocalTime());
                Duration duration = Duration.between(template.getScheduledDeparture(), template.getScheduledArrival());
                LocalDateTime arrival = departure.plus(duration);

                if (arrival.isBefore(fromInclusive) || !departure.isBefore(toExclusive)) {
                    continue;
                }

                String key = naturalKey(template.getFlightCode(), departure);
                if (existingByNaturalKey.containsKey(key)) {
                    continue;
                }

                String generatedCode = generatedCode(template.getFlightCode(), departure.toLocalDate());
                if (existingCodes.contains(generatedCode)) {
                    continue;
                }

                Flight clone = Flight.builder()
                        .flightCode(generatedCode)
                        .originAirport(template.getOriginAirport())
                        .destinationAirport(template.getDestinationAirport())
                        .isInterContinental(template.getIsInterContinental())
                        .maxCapacity(template.getMaxCapacity())
                        .currentLoad(0)
                        .scheduledDeparture(departure)
                        .scheduledArrival(arrival)
                        .status(FlightStatus.SCHEDULED)
                        .transitTimeDays(template.getTransitTimeDays())
                        .build();
                generated.add(clone);
                existingCodes.add(generatedCode);
                existingByNaturalKey.put(key, clone);
            }
            cursor = cursor.plusDays(1);
        }

        if (!generated.isEmpty()) {
            try {
                flightRepository.saveAll(generated);
                log.info("Generados {} vuelos recurrentes para ventana {} -> {}", generated.size(), fromInclusive, toExclusive);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Insercion concurrente o repetida de vuelos recurrentes ignorada: {}", ex.getMessage());
            }
        }

        List<Flight> visibleFlights = new ArrayList<>(existing);
        visibleFlights.addAll(generated);
        return visibleFlights.stream()
                .filter(f -> f.getScheduledDeparture() != null)
                .filter(f -> !f.getScheduledDeparture().isBefore(fromInclusive))
                .filter(f -> f.getScheduledDeparture().isBefore(toExclusive))
                .sorted(Comparator.comparing(Flight::getScheduledDeparture))
                .toList();
    }

    @Transactional
    public void ensureFlightsForSimulationWindow(LocalDateTime anchor) {
        if (anchor == null) return;
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        int days = config == null || config.getSimulationDays() == null ? 3 : Math.max(1, Math.min(3, config.getSimulationDays()));
        LocalDateTime from = anchor.toLocalDate().atStartOfDay();
        LocalDateTime to = anchor.toLocalDate().plusDays(days).atStartOfDay();
        String key = from + "|" + to;
        if (ensuredWindows.putIfAbsent(key, LocalDateTime.now()) != null) {
            return;
        }
        ensureFlightsForWindow(from, to);
    }

    @Transactional(readOnly = true)
    public List<Flight> availableFlightsForShipment(LocalDateTime registrationDate) {
        LocalDateTime base = registrationDate == null ? LocalDateTime.now() : registrationDate;
        LocalDateTime to = base.plusDays(3);
        return flightRepository.findSchedulableFlightsBetween(base, to);
    }

    private String naturalKey(Flight flight) {
        return naturalKey(flight.getFlightCode(), flight.getScheduledDeparture());
    }

    private String naturalKey(String flightCode, LocalDateTime departure) {
        return flightCode + "|" + departure;
    }

    private String generatedCode(String baseCode, LocalDate day) {
        return baseCode + RECURRENT_SUFFIX + day;
    }
}
