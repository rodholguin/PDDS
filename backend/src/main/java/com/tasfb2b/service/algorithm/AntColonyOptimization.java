package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Implementación de Ant Colony Optimization (ACO) para planificación de rutas.
 *
 * <h3>Analogía del problema:</h3>
 * <ul>
 *   <li>Los <b>nodos</b> del grafo son los aeropuertos.</li>
 *   <li>Las <b>aristas</b> son los vuelos disponibles entre pares de aeropuertos.</li>
 *   <li>Las <b>hormigas</b> construyen rutas desde el origen hasta el destino
 *       del envío, eligiendo la siguiente arista de forma probabilística.</li>
 *   <li>Las <b>feromonas</b> se depositan en aristas de rutas de alta calidad
 *       y se evaporan con el tiempo para evitar convergencia prematura.</li>
 * </ul>
 *
 * <h3>Fórmula de probabilidad de selección de arista (vuelo f):</h3>
 * <pre>
 *   P(f) = [τ(f)^α × η(f)^β] / Σ[τ(k)^α × η(k)^β]
 *
 *   τ(f)  = nivel de feromona del vuelo f
 *   η(f)  = heurística de f = 1 / (loadPct(f) + 1) × 1 / (airportLoadPct(dest) + 1)
 *   α     = peso de feromona (importancia de experiencia pasada)
 *   β     = peso de heurística (importancia de información local)
 * </pre>
 *
 * <h3>Refuerzo de feromonas:</h3>
 * Las rutas con menor saturación (aeropuertos y vuelos menos cargados) reciben
 * mayor depósito de feromona, guiando futuras hormigas hacia caminos anticolapso.
 */
@Service("antColonyOptimization")
@Slf4j
public class AntColonyOptimization implements RouteOptimizer {

    // ── Parámetros configurables ─────────────────────────────────────────────
    private int    numAnts         = 50;
    private int    iterations      = 100;
    private double evaporationRate = 0.1;   // ρ — fracción de feromona que se evapora
    private double alpha           = 1.0;   // peso de feromona
    private double beta            = 2.0;   // peso de heurística
    private double initialPheromone = 1.0;  // τ₀ — nivel inicial en todas las aristas

    /**
     * Matriz de feromonas: flightId → nivel τ.
     * Una feromona alta en un vuelo indica que rutas pasadas que lo usaron
     * tuvieron buena calidad (poco colapso).
     */
    private final Map<Long, Double> pheromones = new HashMap<>();

    public void setNumAnts(int numAnts) {
        this.numAnts = Math.max(20, numAnts);
    }

    public void setIterations(int iterations) {
        this.iterations = Math.max(30, iterations);
    }

    public void setEvaporationRate(double evaporationRate) {
        this.evaporationRate = Math.max(0.02, Math.min(0.4, evaporationRate));
    }

    public void setAlpha(double alpha) {
        this.alpha = Math.max(0.5, Math.min(3.0, alpha));
    }

    public void setBeta(double beta) {
        this.beta = Math.max(1.0, Math.min(5.0, beta));
    }

    // ── RouteOptimizer ───────────────────────────────────────────────────────

    @Override
    public String getAlgorithmName() {
        return "Ant Colony Optimization";
    }

    @Override
    public List<TravelStop> planRoute(Shipment shipment,
                                      List<Flight> availableFlights,
                                      List<Airport> airports) {
        log.debug("[ACO] Planificando ruta para envío {} ({} maletas)",
                shipment.getShipmentCode(), shipment.getLuggageCount());

        // 1. Inicializar feromonas para los vuelos disponibles
        initialize(availableFlights);

        List<TravelStop> bestSolution = Collections.emptyList();
        double bestQuality = Double.NEGATIVE_INFINITY;

        for (int iter = 0; iter < iterations; iter++) {

            List<List<TravelStop>> antSolutions = new ArrayList<>();

            // 2. Cada hormiga construye una solución
            for (int ant = 0; ant < numAnts; ant++) {
                List<TravelStop> solution = buildSolution(shipment, availableFlights, airports);
                if (!solution.isEmpty()) {
                    antSolutions.add(solution);
                    double quality = evaluateSolution(solution, shipment);
                    if (quality > bestQuality) {
                        bestQuality = quality;
                        bestSolution = solution;
                    }
                }
            }

            // 3. Actualizar feromonas con las soluciones de esta iteración
            evaporate();
            updatePheromones(antSolutions, shipment);

            log.trace("[ACO] Iter {}/{} — mejor calidad: {:.2f}", iter + 1, iterations, bestQuality);
        }

        return bestSolution;
    }

    @Override
    public List<TravelStop> replanRoute(Shipment shipment,
                                        TravelStop failedStop,
                                        List<Flight> availableFlights) {
        log.warn("[ACO] Replanificando {} desde {} (aeropuerto {})",
                shipment.getShipmentCode(),
                failedStop.getStopOrder(),
                failedStop.getAirport().getIcaoCode());

        Shipment partial = Shipment.builder()
                .shipmentCode(shipment.getShipmentCode() + "-A")
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

        List<TravelStop> route = planRoute(partial, availableFlights, airports);
        int base = failedStop.getStopOrder();
        for (TravelStop stop : route) {
            stop.setStopOrder(base + stop.getStopOrder());
        }
        return route;
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

            double urgencyPenalty = shipment.getDeadline() == null ? 18.0
                    : Math.max(1.0, ChronoUnit.HOURS.between(LocalDateTime.now(), shipment.getDeadline()));
            operationalCost += shipment.getLuggageCount() * 0.24 + urgencyPenalty * 0.35;
            utilization += shipment.getProgressPercentage() * 0.79;
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

    // ── Métodos internos de ACO ───────────────────────────────────────────────

    /**
     * Inicializa la matriz de feromonas con {@code initialPheromone} en todos
     * los vuelos disponibles.
     *
     * <p>Se debe llamar una vez antes de iniciar las iteraciones de cada llamada
     * a {@link #planRoute}. Resetear feromonas entre llamadas evita que la
     * experiencia de un envío anterior sesgue la planificación del siguiente
     * (aunque en simulaciones largas podría ser deseable mantener el estado).
     *
     * TODO: Considerar si conviene preservar las feromonas entre llamadas
     *       (aprendizaje global) o resetear (independencia por envío).
     */
    void initialize(List<Flight> availableFlights) {
        pheromones.clear();
        for (Flight f : availableFlights) {
            pheromones.put(f.getId(), initialPheromone);
        }
        // TODO: Opcionalmente cargar feromonas guardadas de iteraciones anteriores
        //       para acelerar la convergencia en simulaciones largas.
    }

    /**
     * Una hormiga construye una solución completa: ruta desde
     * {@code shipment.originAirport} hasta {@code shipment.destinationAirport}.
     *
     * <p>Algoritmo de construcción:
     * <ol>
     *   <li>Empezar en el aeropuerto origen; añadir TravelStop de partida (sin vuelo).</li>
     *   <li>En cada paso, obtener los vuelos disponibles que salen del aeropuerto actual
     *       y van hacia aeropuertos no visitados.</li>
     *   <li>Si hay vuelo directo al destino → tomarlo (regla greedy de cierre).</li>
     *   <li>Si no, seleccionar el vuelo siguiente con la probabilidad P(f) (ver clase).</li>
     *   <li>Añadir TravelStop y avanzar al aeropuerto destino del vuelo elegido.</li>
     *   <li>Repetir hasta llegar al destino final o quedarse sin vuelos (ruta fallida).</li>
     *   <li>Limitar a máximo 3 paradas para rutas inter-continentales, 2 para intra.</li>
     * </ol>
     *
     * TODO: Implementar los pasos anteriores.
     *       Actualmente retorna lista vacía (esqueleto).
     */
    List<TravelStop> buildSolution(Shipment shipment,
                                   List<Flight> availableFlights,
                                   List<Airport> airports) {
        Airport origin = shipment.getOriginAirport();
        Airport destination = shipment.getDestinationAirport();
        if (origin == null || destination == null) return Collections.emptyList();

        List<Flight> direct = availableFlights.stream()
                .filter(flight -> flight.getStatus() == FlightStatus.SCHEDULED)
                .filter(flight -> flight.getOriginAirport().getId().equals(origin.getId()))
                .filter(flight -> flight.getDestinationAirport().getId().equals(destination.getId()))
                .filter(flight -> flight.getAvailableCapacity() >= shipment.getLuggageCount())
                .sorted(Comparator.comparingDouble(Flight::getLoadPct))
                .toList();

        if (!direct.isEmpty()) {
            Flight best = direct.get(0);
            return List.of(
                    TravelStop.builder()
                            .airport(origin)
                            .flight(null)
                            .stopOrder(0)
                            .stopStatus(StopStatus.COMPLETED)
                            .scheduledArrival(best.getScheduledDeparture())
                            .actualArrival(best.getScheduledDeparture())
                            .build(),
                    TravelStop.builder()
                            .airport(destination)
                            .flight(best)
                            .stopOrder(1)
                            .stopStatus(StopStatus.PENDING)
                            .scheduledArrival(best.getScheduledArrival())
                            .build()
            );
        }

        List<TravelStop> bestRoute = Collections.emptyList();
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Flight first : availableFlights) {
            if (first.getStatus() != FlightStatus.SCHEDULED) continue;
            if (!first.getOriginAirport().getId().equals(origin.getId())) continue;
            if (first.getAvailableCapacity() < shipment.getLuggageCount()) continue;

            Airport hub = first.getDestinationAirport();
            for (Flight second : availableFlights) {
                if (second.getStatus() != FlightStatus.SCHEDULED) continue;
                if (!second.getOriginAirport().getId().equals(hub.getId())) continue;
                if (!second.getDestinationAirport().getId().equals(destination.getId())) continue;
                if (second.getAvailableCapacity() < shipment.getLuggageCount()) continue;

                List<TravelStop> candidate = List.of(
                        TravelStop.builder()
                                .airport(origin)
                                .flight(null)
                                .stopOrder(0)
                                .stopStatus(StopStatus.COMPLETED)
                                .scheduledArrival(first.getScheduledDeparture())
                                .actualArrival(first.getScheduledDeparture())
                                .build(),
                        TravelStop.builder()
                                .airport(hub)
                                .flight(first)
                                .stopOrder(1)
                                .stopStatus(StopStatus.PENDING)
                                .scheduledArrival(first.getScheduledArrival())
                                .build(),
                        TravelStop.builder()
                                .airport(destination)
                                .flight(second)
                                .stopOrder(2)
                                .stopStatus(StopStatus.PENDING)
                                .scheduledArrival(second.getScheduledArrival())
                                .build()
                );

                double score = evaluateSolution(candidate, shipment);
                if (score > bestScore) {
                    bestScore = score;
                    bestRoute = candidate;
                }
            }
        }

        return bestRoute;
    }

    /**
     * Actualiza las feromonas tras una iteración completa depositando
     * cantidad proporcional a la calidad de cada solución.
     *
     * <p>Fórmula de depósito:
     * <pre>
     *   Δτ(f) = Q / (1 + saturatedAirports + overloadedFlights)
     *
     *   Q = constante de depósito (aquí: 100.0)
     *   saturatedAirports  = aeropuertos CRITICO en la ruta de la solución
     *   overloadedFlights  = vuelos con carga > 80% en la ruta
     * </pre>
     *
     * <p>Solo depositan feromonas las soluciones que llegan al destino
     * antes del deadline. Las rutas fallidas no refuerzan ningún arco.
     *
     * TODO: Implementar el cálculo de Δτ y la actualización del mapa pheromones.
     *       Actualmente no modifica el mapa (esqueleto).
     */
    void updatePheromones(List<List<TravelStop>> antSolutions, Shipment shipment) {
        final double Q = 100.0;

        for (List<TravelStop> solution : antSolutions) {
            double quality = evaluateSolution(solution, shipment);
            if (quality <= 0) continue; // solución inválida, no refuerza

            double delta = Q / (1.0 + Math.max(0, 1000.0 - quality));

            for (TravelStop stop : solution) {
                if (stop.getFlight() == null) continue;
                Long flightId = stop.getFlight().getId();
                pheromones.merge(flightId, delta, Double::sum);
            }
        }
    }

    /**
     * Evapora feromonas en todos los arcos multiplicando por (1 − evaporationRate).
     *
     * <p>La evaporación previene que el algoritmo converja demasiado rápido hacia
     * una solución subóptima. Con {@code evaporationRate = 0.1}, cada iteración
     * retiene el 90 % de las feromonas previas.
     *
     * <p>Se aplica un nivel mínimo de feromona {@code τ_min = 0.01} para garantizar
     * que todos los arcos sigan siendo explorables.
     *
     * TODO: Añadir clamping de τ_min para evitar que arcos caigan a 0.
     */
    void evaporate() {
        final double tauMin = 0.01;
        pheromones.replaceAll((flightId, tau) ->
                Math.max(tauMin, tau * (1.0 - evaporationRate)));
    }

    // ── Helpers internos ─────────────────────────────────────────────────────

    /**
     * Calcula la probabilidad de seleccionar cada vuelo candidato usando
     * la regla de transición proporcional de ACO.
     *
     * TODO: Implementar ruleta proporcional:
     *   1. Para cada vuelo f en candidates:
     *      weight(f) = τ(f)^alpha × η(f)^beta
     *      donde η(f) = 1 / ((loadPct(f) + 1) × (destAirportLoadPct + 1))
     *   2. Normalizar: P(f) = weight(f) / ∑ weight(k)
     *   3. Seleccionar con ruleta (roulette wheel selection)
     */
    Flight rouletteSelect(List<Flight> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        double total = 0.0;
        List<Double> weights = new ArrayList<>(candidates.size());

        for (Flight flight : candidates) {
            double tau = pheromones.getOrDefault(flight.getId(), initialPheromone);
            double eta = 1.0 / (1.0 + flight.getLoadPct());
            double weight = Math.pow(tau, alpha) * Math.pow(eta, beta);
            weights.add(weight);
            total += weight;
        }

        double roll = Math.random() * total;
        double cumulative = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights.get(i);
            if (roll <= cumulative) return candidates.get(i);
        }

        return candidates.get(candidates.size() - 1);
    }

    /**
     * Calcula la calidad (fitness) de una solución de hormiga.
     * Mayor valor = menor riesgo de colapso.
     *
     * TODO: Reutilizar la misma lógica de penalizaciones que GeneticAlgorithm.fitness()
     *       para que los resultados sean comparables entre algoritmos.
     */
    double evaluateSolution(List<TravelStop> stops, Shipment shipment) {
        if (stops == null || stops.isEmpty()) return 0.0;
        double score = 1000.0;

        double transitHours = stops.stream()
                .filter(s -> s.getFlight() != null)
                .mapToDouble(s -> s.getFlight().getTransitTimeDays() * 24.0)
                .sum();

        LocalDateTime originTime = shipment.getRegistrationDate() == null ? LocalDateTime.now() : shipment.getRegistrationDate();
        LocalDateTime eta = originTime.plusHours((long) Math.ceil(transitHours));

        if (shipment.getDeadline() != null && eta.isAfter(shipment.getDeadline())) {
            score -= 420;
        }

        for (TravelStop stop : stops) {
            if (stop.getAirport().getOccupancyPct() > 90.0) {
                score -= 2.2 * (stop.getAirport().getOccupancyPct() - 90.0);
            }
            if (stop.getFlight() != null && stop.getFlight().getLoadPct() > 80.0) {
                score -= 1.1 * (stop.getFlight().getLoadPct() - 80.0);
            }
        }

        score -= 14 * Math.max(0, stops.size() - 2);
        return score;
    }

    private boolean withinPeriod(Shipment shipment, LocalDate from, LocalDate to) {
        if (shipment.getRegistrationDate() == null) return true;
        LocalDate day = shipment.getRegistrationDate().toLocalDate();
        boolean afterFrom = from == null || !day.isBefore(from);
        boolean beforeTo = to == null || !day.isAfter(to);
        return afterFrom && beforeTo;
    }
}
