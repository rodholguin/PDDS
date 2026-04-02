package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

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

        // TODO: Filtrar availableFlights para incluir solo vuelos que salen de
        //       failedStop.getAirport() o de aeropuertos conectados desde allí.
        // TODO: Penalizar (reducir feromona a τ_min) los vuelos del failedStop
        //       para que las nuevas hormigas eviten ese camino.
        // TODO: Reducir iterations a la mitad para replanificación rápida.
        // TODO: Ajustar stopOrder de la nueva ruta partiendo de failedStop.getStopOrder()+1.

        List<Flight> filteredFlights = availableFlights.stream()
                .filter(f -> f.getOriginAirport().getId()
                              .equals(failedStop.getAirport().getId()))
                .collect(Collectors.toList());

        // TODO: llamar a planRoute con un Shipment parcial cuyo origen = failedStop.airport
        return Collections.emptyList();
    }

    @Override
    public OptimizationResult evaluatePerformance(List<Shipment> shipments,
                                                  LocalDate from,
                                                  LocalDate to) {
        // TODO: Filtrar shipments por registrationDate en [from, to]
        // TODO: Para cada envío: planRoute y registrar si llega antes del deadline
        // TODO: Acumular métricas análogas a GeneticAlgorithm.evaluatePerformance()
        // TODO: Comparar con GeneticAlgorithm para elegir el mejor en RoutePlannerService

        int total = shipments.size();
        return OptimizationResult.builder()
                .algorithmName(getAlgorithmName())
                .completedShipments(0)
                .completedPct(total == 0 ? 0.0 : 0.0 / total * 100)
                .avgTransitHours(0.0)
                .totalReplanning(0)
                .operationalCost(0.0)
                .flightUtilizationPct(0.0)
                .saturatedAirports(0)
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
        List<TravelStop> route = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        Airport current = shipment.getOriginAirport();
        visited.add(current.getId());

        // TODO: 1. Crear TravelStop inicial (origen, sin vuelo, stopOrder=0)

        int maxStops = Boolean.TRUE.equals(shipment.getIsInterContinental()) ? 3 : 2;

        for (int step = 0; step < maxStops; step++) {
            Airport finalCurrent = current;

            // TODO: 2. Obtener vuelos candidatos desde current hacia aeropuertos no visitados
            List<Flight> candidates = availableFlights.stream()
                    .filter(f -> f.getOriginAirport().getId().equals(finalCurrent.getId()))
                    .filter(f -> !visited.contains(f.getDestinationAirport().getId()))
                    .filter(f -> f.getAvailableCapacity() >= shipment.getLuggageCount())
                    .collect(Collectors.toList());

            if (candidates.isEmpty()) break;

            // TODO: 3. Comprobar si hay vuelo directo al destino → tomarlo inmediatamente
            // TODO: 4. Si no, aplicar selección probabilística rouletteSelect(candidates)
            // TODO: 5. Crear TravelStop con el vuelo elegido y añadir a route
            // TODO: 6. Actualizar current y visited
        }

        // TODO: Validar que route termina en shipment.getDestinationAirport()
        //       Si no → retornar Collections.emptyList() (hormiga fracasó)
        return Collections.emptyList();
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
                // TODO: pheromones.merge(flightId, delta, Double::sum)
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

        // TODO: Implementar selección proporcional (ver javadoc arriba)
        // Actualmente: retorna el primero (esqueleto)
        return candidates.get(0);
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
        // TODO: Aplicar penalizaciones equivalentes a GeneticAlgorithm.fitness()
        return score;
    }
}
