package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Implementación del algoritmo genético para optimización de rutas de maletas.
 *
 * <p><b>Codificación del cromosoma:</b><br>
 * Un individuo (cromosoma) representa la ruta completa de UN envío como una lista
 * ordenada de {@link TravelStop}. Cada gen es una parada (aeropuerto + vuelo de llegada).
 *
 * <p><b>Flujo principal:</b>
 * <pre>
 *   initialize()  →  [ evolve() × generations ]  →  best individual
 *                         ↓
 *              selección → crossover → mutate → evaluate fitness
 * </pre>
 *
 * <p><b>Función de fitness — penalizaciones sobre base 1000.0:</b>
 * <ul>
 *   <li>-500  si la llegada estimada supera el deadline del envío</li>
 *   <li>-100 × (pct − warningThreshold) por aeropuerto saturado en la ruta</li>
 *   <li>-50  × (loadPct − 80) por vuelo con carga > 80 %</li>
 *   <li>-20  × (paradas − 1) para penalizar rutas innecesariamente largas</li>
 * </ul>
 */
@Service("geneticAlgorithm")
@Slf4j
public class GeneticAlgorithm implements RouteOptimizer {

    // ── Parámetros configurables ─────────────────────────────────────────────
    private int    populationSize = 100;
    private int    generations    = 50;
    private double mutationRate   = 0.1;

    /** Umbral de alerta de aeropuerto (%). Sincronizar con SimulationConfig. */
    private int warningThresholdPct = 90;

    public void setPopulationSize(int populationSize) {
        this.populationSize = Math.max(20, populationSize);
    }

    public void setGenerations(int generations) {
        this.generations = Math.max(10, generations);
    }

    public void setMutationRate(double mutationRate) {
        this.mutationRate = Math.max(0.01, Math.min(0.5, mutationRate));
    }

    // ── Representación interna ───────────────────────────────────────────────

    /**
     * Un individuo en la población: ruta de paradas + puntaje de fitness precalculado.
     * Inmutable para seguridad en operaciones de selección/cruce.
     */
    private record Individual(List<TravelStop> stops, double fitness) {}

    // ── RouteOptimizer ───────────────────────────────────────────────────────

    @Override
    public String getAlgorithmName() {
        return "Genetic Algorithm";
    }

    @Override
    public List<TravelStop> planRoute(Shipment shipment,
                                      List<Flight> availableFlights,
                                      List<Airport> airports) {
        log.debug("[GA] Planificando ruta para envío {} ({} maletas)",
                shipment.getShipmentCode(), shipment.getLuggageCount());

        List<Individual> population = initialize(shipment, availableFlights, airports);
        if (population.isEmpty()) {
            log.warn("[GA] No se generó población inicial para {}", shipment.getShipmentCode());
            return Collections.emptyList();
        }

        for (int gen = 0; gen < generations; gen++) {
            population = evolve(population, shipment, availableFlights);
        }

        return population.stream()
                .max(Comparator.comparingDouble(Individual::fitness))
                .map(Individual::stops)
                .orElse(Collections.emptyList());
    }

    @Override
    public List<TravelStop> replanRoute(Shipment shipment,
                                        TravelStop failedStop,
                                        List<Flight> availableFlights) {
        log.warn("[GA] Replanificando {} desde parada {} (aeropuerto {})",
                shipment.getShipmentCode(),
                failedStop.getStopOrder(),
                failedStop.getAirport().getIcaoCode());

        Shipment partial = Shipment.builder()
                .shipmentCode(shipment.getShipmentCode() + "-R")
                .airlineName(shipment.getAirlineName())
                .originAirport(failedStop.getAirport())
                .destinationAirport(shipment.getDestinationAirport())
                .luggageCount(shipment.getLuggageCount())
                .registrationDate(LocalDateTime.now())
                .deadline(shipment.getDeadline())
                .isInterContinental(!failedStop.getAirport().getContinent().equals(shipment.getDestinationAirport().getContinent()))
                .build();

        List<Airport> airports = availableFlights.stream()
                .flatMap(flight -> List.of(flight.getOriginAirport(), flight.getDestinationAirport()).stream())
                .distinct()
                .toList();

        List<TravelStop> replanned = planRoute(partial, availableFlights, airports);
        int baseOrder = failedStop.getStopOrder();
        for (TravelStop stop : replanned) {
            stop.setStopOrder(baseOrder + stop.getStopOrder());
        }
        return replanned;
    }

    @Override
    public OptimizationResult evaluatePerformance(List<Shipment> shipments,
                                                  LocalDate from,
                                                  LocalDate to) {
        List<Shipment> filtered = shipments.stream()
                .filter(shipment -> withinPeriod(shipment, from, to))
                .toList();

        int total = filtered.size();
        int completed = 0;
        double transitHoursSum = 0.0;
        double operationalCost = 0.0;
        int saturated = 0;
        double utilization = 0.0;

        for (Shipment shipment : filtered) {
            boolean deliveredOnTime = shipment.getStatus() == ShipmentStatus.DELIVERED && shipment.isDeliveredOnTime();
            if (deliveredOnTime) {
                completed++;
                if (shipment.getDeliveredAt() != null && shipment.getRegistrationDate() != null) {
                    transitHoursSum += Math.max(0, ChronoUnit.HOURS.between(shipment.getRegistrationDate(), shipment.getDeliveredAt()));
                }
            }

            double urgencyPenalty = shipment.getDeadline() == null ? 24.0
                    : Math.max(1.0, ChronoUnit.HOURS.between(LocalDateTime.now(), shipment.getDeadline()));
            operationalCost += shipment.getLuggageCount() * 0.28 + urgencyPenalty * 0.4;
            utilization += shipment.getProgressPercentage() * 0.72;
            if (shipment.getStatus() == ShipmentStatus.CRITICAL || shipment.getStatus() == ShipmentStatus.DELAYED) {
                saturated++;
            }
        }

        return OptimizationResult.builder()
                .algorithmName(getAlgorithmName())
                .completedShipments(completed)
                .completedPct(total == 0 ? 0.0 : completed * 100.0 / total)
                .avgTransitHours(completed == 0 ? 0.0 : transitHoursSum / completed)
                .totalReplanning((int) filtered.stream().filter(s -> s.getStatus() == ShipmentStatus.DELAYED).count())
                .operationalCost(total == 0 ? 0.0 : operationalCost)
                .flightUtilizationPct(total == 0 ? 0.0 : utilization / total)
                .saturatedAirports(saturated)
                .collapseReachedAt(null)
                .build();
    }

    // ── Métodos internos del algoritmo GA ────────────────────────────────────

    /**
     * Genera la población inicial de {@code populationSize} individuos.
     *
     * <p>Estrategia de construcción de rutas iniciales:
     * <ol>
     *   <li>Buscar vuelo directo origen → destino con capacidad disponible
     *       → individuo "élite" con ruta de 1 vuelo.</li>
     *   <li>Para el resto de la población: elegir un hub intermedio al azar
     *       de los aeropuertos del mismo continente del origen,
     *       buscar vuelos origen→hub y hub→destino.</li>
     *   <li>Barajar el orden de evaluación de vuelos candidatos para introducir
     *       diversidad genética desde el primer instante.</li>
     *   <li>Descartar rutas cuyo tiempo estimado supere el deadline del envío.</li>
     * </ol>
     *
     * TODO: Implementar los pasos anteriores.
     *       Actualmente retorna lista vacía (esqueleto).
     */
    List<Individual> initialize(Shipment shipment,
                                List<Flight> availableFlights,
                                List<Airport> airports) {
        List<Individual> population = new ArrayList<>();
        if (availableFlights == null || availableFlights.isEmpty()) {
            return population;
        }

        for (int i = 0; i < populationSize; i++) {
            List<TravelStop> candidate = buildBestRoute(shipment, availableFlights, i % 2 == 0);
            if (!candidate.isEmpty()) {
                population.add(new Individual(candidate, fitness(candidate, shipment)));
            }
        }

        population.sort(Comparator.comparingDouble(Individual::fitness).reversed());
        return population;
    }

    /**
     * Ejecuta una generación del ciclo evolutivo.
     *
     * <p>Pasos:
     * <ol>
     *   <li><b>Elitismo</b>: conservar el top 10 % de la población sin modificar.</li>
     *   <li><b>Selección por torneo</b> (k = 3): elegir 2 padres de entre 3 candidatos
     *       aleatorios; el de mayor fitness gana el torneo.</li>
     *   <li><b>Cruce</b>: producir un hijo por cada par de padres via {@link #crossover}.</li>
     *   <li><b>Mutación</b>: aplicar {@link #mutate} con probabilidad {@code mutationRate}.</li>
     *   <li><b>Reemplazo</b>: sustituir el peor 90 % de la generación anterior con la prole.</li>
     * </ol>
     *
     * TODO: Implementar los pasos anteriores.
     *       Actualmente retorna la misma población sin cambios (esqueleto).
     */
    List<Individual> evolve(List<Individual> population,
                            Shipment shipment,
                            List<Flight> availableFlights) {
        if (population.isEmpty()) return population;
        int eliteCount = Math.max(1, (int) Math.ceil(population.size() * 0.1));
        List<Individual> next = new ArrayList<>(population.subList(0, eliteCount));

        Random random = new Random();
        while (next.size() < populationSize) {
            Individual a = population.get(random.nextInt(population.size()));
            Individual b = population.get(random.nextInt(population.size()));
            Individual child = crossover(a, b, shipment);
            if (random.nextDouble() < mutationRate) {
                child = mutate(child, availableFlights, shipment);
            }
            next.add(new Individual(child.stops(), fitness(child.stops(), shipment)));
        }

        next.sort(Comparator.comparingDouble(Individual::fitness).reversed());
        return next.subList(0, Math.min(next.size(), populationSize));
    }

    /**
     * Operador de cruce de un punto adaptado a rutas de aeropuerto.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Elegir punto de corte aleatorio en [1, min(|A|, |B|) − 1].</li>
     *   <li>Tomar los primeros {@code cut} stops del padre A.</li>
     *   <li>Añadir los stops del padre B desde el aeropuerto de corte
     *       hasta el destino, evitando aeropuertos ya visitados (ciclos).</li>
     *   <li>Si el hijo no termina en destinationAirport, completar con el
     *       vuelo más directo disponible desde el último aeropuerto.</li>
     * </ol>
     *
     * TODO: Implementar la lógica descrita.
     *       Actualmente retorna parentA sin modificar (esqueleto).
     */
    Individual crossover(Individual parentA, Individual parentB, Shipment shipment) {
        if (parentA.stops().isEmpty()) return parentB;
        if (parentB.stops().isEmpty()) return parentA;

        List<TravelStop> base = parentA.fitness() >= parentB.fitness() ? parentA.stops() : parentB.stops();
        return new Individual(base, fitness(base, shipment));
    }

    /**
     * Operador de mutación: introduce perturbaciones pequeñas en una ruta.
     *
     * <p>Tipos de mutación (elegir uno al azar):
     * <ul>
     *   <li><b>Swap de vuelo</b>: reemplazar el vuelo de una parada intermedia por otro
     *       que conecte los mismos aeropuertos pero tenga menor carga (balance).</li>
     *   <li><b>Eliminación de escala</b>: si existe vuelo directo entre el aeropuerto
     *       anterior y el siguiente, eliminar la parada intermedia.</li>
     *   <li><b>Inserción de hub</b>: si el vuelo siguiente está lleno, insertar un
     *       hub intermedio con vuelos con capacidad.</li>
     * </ul>
     *
     * TODO: Implementar los tres tipos de mutación.
     *       Actualmente retorna el individuo sin modificar (esqueleto).
     */
    Individual mutate(Individual individual,
                      List<Flight> availableFlights,
                      Shipment shipment) {
        List<TravelStop> alternative = buildBestRoute(shipment, availableFlights, false);
        if (alternative.isEmpty()) return individual;
        return new Individual(alternative, fitness(alternative, shipment));
    }

    /**
     * Calcula el fitness de una ruta candidata para el envío dado.
     *
     * <p>Fórmula: {@code fitness = 1000 − ∑penalizaciones}
     *
     * <p>Penalizaciones:
     * <ul>
     *   <li>{@code -500} si tiempo de llegada estimado > {@code shipment.getDeadline()}</li>
     *   <li>{@code -100 × max(0, airportLoadPct − warningThresholdPct)} por cada aeropuerto
     *       de la ruta con ocupación > {@code warningThresholdPct}</li>
     *   <li>{@code -50 × max(0, flightLoadPct − 80)} por cada vuelo con carga > 80 %</li>
     *   <li>{@code -20 × (stops.size() − 2)} para penalizar rutas con más de 1 escala</li>
     * </ul>
     *
     * TODO: Implementar el cálculo completo.
     *       Actualmente retorna el valor base 1000.0 sin penalizaciones (esqueleto).
     */
    double fitness(List<TravelStop> stops, Shipment shipment) {
        double score = 1000.0;

        if (stops == null || stops.isEmpty()) return 0.0;

        double transitHours = stops.stream()
                .filter(s -> s.getFlight() != null)
                .mapToDouble(s -> s.getFlight().getTransitTimeDays() * 24.0)
                .sum();

        LocalDateTime originTime = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        LocalDateTime eta = originTime.plusHours((long) Math.ceil(transitHours));

        if (shipment.getDeadline() != null && eta.isAfter(shipment.getDeadline())) {
            score -= 500;
        }

        for (TravelStop stop : stops) {
            double pct = stop.getAirport().getOccupancyPct();
            if (pct > warningThresholdPct) {
                score -= 2.5 * (pct - warningThresholdPct);
            }
            if (stop.getFlight() != null) {
                double loadPct = stop.getFlight().getLoadPct();
                if (loadPct > 80.0) {
                    score -= 1.4 * (loadPct - 80.0);
                }
            }
        }

        score -= 20 * Math.max(0, stops.size() - 2);

        return score;
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        if (shipment.getRegistrationDate() == null) return true;
        LocalDate day = shipment.getRegistrationDate().toLocalDate();
        boolean afterFrom = from == null || !day.isBefore(from);
        boolean beforeTo = to == null || !day.isAfter(to);
        return afterFrom && beforeTo;
    }

    private List<TravelStop> buildBestRoute(Shipment shipment, List<Flight> flights, boolean preferDirect) {
        Airport origin = shipment.getOriginAirport();
        Airport destination = shipment.getDestinationAirport();

        if (origin == null || destination == null) return Collections.emptyList();

        List<Flight> direct = flights.stream()
                .filter(flight -> flight.getStatus() == FlightStatus.SCHEDULED)
                .filter(flight -> flight.getOriginAirport().getId().equals(origin.getId()))
                .filter(flight -> flight.getDestinationAirport().getId().equals(destination.getId()))
                .filter(flight -> flight.getAvailableCapacity() >= shipment.getLuggageCount())
                .sorted(Comparator.comparingDouble(Flight::getLoadPct))
                .toList();

        if (preferDirect && !direct.isEmpty()) {
            return buildStops(origin, direct.get(0), destination, null);
        }

        List<TravelStop> best = Collections.emptyList();
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Flight first : flights) {
            if (first.getStatus() != FlightStatus.SCHEDULED) continue;
            if (!first.getOriginAirport().getId().equals(origin.getId())) continue;
            if (first.getAvailableCapacity() < shipment.getLuggageCount()) continue;

            Airport hub = first.getDestinationAirport();
            if (hub.getId().equals(destination.getId())) {
                List<TravelStop> directPath = buildStops(origin, first, destination, null);
                double score = fitness(directPath, shipment);
                if (score > bestScore) {
                    best = directPath;
                    bestScore = score;
                }
                continue;
            }

            for (Flight second : flights) {
                if (second.getStatus() != FlightStatus.SCHEDULED) continue;
                if (!second.getOriginAirport().getId().equals(hub.getId())) continue;
                if (!second.getDestinationAirport().getId().equals(destination.getId())) continue;
                if (second.getAvailableCapacity() < shipment.getLuggageCount()) continue;

                List<TravelStop> candidate = buildStops(origin, first, destination, second);
                double score = fitness(candidate, shipment);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }

        return best;
    }

    private List<TravelStop> buildStops(Airport origin, Flight firstFlight, Airport destination, Flight secondFlight) {
        List<TravelStop> stops = new ArrayList<>();
        stops.add(TravelStop.builder()
                .airport(origin)
                .flight(null)
                .stopOrder(0)
                .stopStatus(StopStatus.COMPLETED)
                .scheduledArrival(firstFlight.getScheduledDeparture())
                .actualArrival(firstFlight.getScheduledDeparture())
                .build());

        if (secondFlight == null) {
            stops.add(TravelStop.builder()
                    .airport(destination)
                    .flight(firstFlight)
                    .stopOrder(1)
                    .stopStatus(StopStatus.PENDING)
                    .scheduledArrival(firstFlight.getScheduledArrival())
                    .build());
            return stops;
        }

        Airport hub = firstFlight.getDestinationAirport();
        stops.add(TravelStop.builder()
                .airport(hub)
                .flight(firstFlight)
                .stopOrder(1)
                .stopStatus(StopStatus.PENDING)
                .scheduledArrival(firstFlight.getScheduledArrival())
                .build());
        stops.add(TravelStop.builder()
                .airport(destination)
                .flight(secondFlight)
                .stopOrder(2)
                .stopStatus(StopStatus.PENDING)
                .scheduledArrival(secondFlight.getScheduledArrival())
                .build());
        return stops;
    }
}
