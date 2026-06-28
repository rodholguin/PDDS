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

        // Cargar existentes por DÍA completo (no desde el timestamp exacto): la generación es por día y
        // puede producir vuelos con salida anterior a fromInclusive dentro del mismo día. Si el chequeo
        // de existentes arrancara en fromInclusive, esos clones (de corridas previas, ya en BD) no se
        // verían y se re-generarían → duplicate key sobre flight_code. Alinear ambos al rango de días.
        LocalDateTime existingFrom = fromInclusive.toLocalDate().atStartOfDay();
        LocalDateTime existingTo = toExclusive.minusNanos(1).toLocalDate().plusDays(1).atStartOfDay();
        List<Flight> existing = flightRepository.findFlightsWithinWindow(existingFrom, existingTo);

        // Día patrón = primer vuelo TEMPLATE (no recurrente), estable aunque ya existan clones tempranos.
        LocalDate earliestExistingDate = java.util.Optional.ofNullable(flightRepository.findEarliestTemplateDeparture())
                .map(LocalDateTime::toLocalDate)
                .orElse(null);

        if (earliestExistingDate == null) {
            return existing;
        }

        // Ventana de 3 días: al convertir las horas a UTC, el patrón diario queda repartido en ~2 días
        // calendario (husos ±12h). Tomar solo 1 día dejaba rutas sin template → envíos inviables (CRITICAL).
        List<Flight> templates = flightRepository.findTemplateFlightsWithinWindow(
                        earliestExistingDate.atStartOfDay(),
                        earliestExistingDate.plusDays(3).atStartOfDay())
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
        SimulationConfig config = simulationConfigRepository.findLiveConfigOrFirst();
        int days = config == null || config.getSimulationDays() == null ? 3 : Math.max(1, config.getSimulationDays());
        LocalDateTime from = anchor.toLocalDate().atStartOfDay();
        LocalDateTime to = anchor.toLocalDate().plusDays(days + 3L).atStartOfDay();
        String key = from + "|" + to;
        if (ensuredWindows.putIfAbsent(key, LocalDateTime.now()) != null) {
            return;
        }
        ensureFlightsForWindow(from, to);
    }

    @Transactional(readOnly = true)
    public List<Flight> schedulableFlightsWithinWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            return List.of();
        }
        return flightRepository.findSchedulableFlightsBetween(fromInclusive, toExclusive);
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
