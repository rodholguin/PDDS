package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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
            double bestFitness = population.get(0).fitness();
            log.trace("[GA] Gen {}/{} — mejor fitness: {:.2f}", gen + 1, generations, bestFitness);
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

        // TODO: Construir un envío parcial ficticio cuyo origen sea failedStop.getAirport()
        //       y destino sea shipment.getDestinationAirport(), manteniendo el mismo deadline.
        // TODO: Filtrar availableFlights para incluir solo los que salen de
        //       failedStop.getAirport() o de aeropuertos alcanzables desde allí.
        // TODO: Llamar a planRoute con el envío parcial y los vuelos filtrados.
        // TODO: Ajustar stopOrder de los nuevos TravelStop para que continúen
        //       desde failedStop.getStopOrder() + 1.

        return Collections.emptyList();
    }

    @Override
    public OptimizationResult evaluatePerformance(List<Shipment> shipments,
                                                  LocalDate from,
                                                  LocalDate to) {
        // TODO: Filtrar shipments cuyo registrationDate esté en [from, to].
        // TODO: Para cada envío, ejecutar planRoute y verificar si la ruta estimada
        //       llega antes del deadline (status = DELIVERED).
        // TODO: Acumular: completedShipments, avgTransitHours, operationalCost,
        //       saturatedAirports (aeropuertos que llegaron a CRITICO en la simulación).
        // TODO: Detectar momento de colapso: primera iteración donde TODOS los aeropuertos
        //       de la ruta están en CRITICO → setear collapseReachedAt.
        // TODO: Calcular flightUtilizationPct como promedio de (currentLoad/maxCapacity)
        //       de todos los vuelos utilizados.

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
        // TODO: 1. Encontrar vuelos directos origen→destino (isInterContinental coincide)
        // TODO: 2. Construir ruta directa: TravelStop(order=0, origin) + TravelStop(order=1, dest, flight)
        // TODO: 3. Evaluar fitness de la ruta directa → agregar como élite
        // TODO: 4. Para los restantes individuos: construir rutas con 1 hub intermedio
        //    a. Filtrar aeropuertos del continente origen como hubs candidatos
        //    b. Para cada hub: buscar vuelo origen→hub y vuelo hub→destino
        //    c. Construir ruta de 3 paradas y evaluar fitness
        // TODO: 5. Ordenar la población por fitness descendente antes de retornar
        return new ArrayList<>();
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
        // TODO: 1. Separar élite (top eliteCount = populationSize * 0.1)
        // TODO: 2. Bucle hasta completar populationSize - eliteCount hijos:
        //    a. tournamentSelect(population) × 2 → parentA, parentB
        //    b. child = crossover(parentA, parentB, shipment)
        //    c. if (random() < mutationRate) child = mutate(child, availableFlights, shipment)
        //    d. child = new Individual(child.stops(), fitness(child.stops(), shipment))
        // TODO: 3. Nueva población = élite + hijos, ordenada por fitness desc
        return population;
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
        // TODO: 1. Validar que ambos padres tienen stops.size() >= 2
        // TODO: 2. Calcular punto de corte: int cut = random.nextInt(min(|A|,|B|) - 1) + 1
        // TODO: 3. head = parentA.stops().subList(0, cut)
        // TODO: 4. Buscar en parentB.stops() el primer stop cuyo aeropuerto == head.last().airport
        //          y tomar el subList desde ese índice hasta el final
        // TODO: 5. Verificar y reparar conectividad (cada par de stops consecutivos
        //          debe tener un vuelo que los conecte en availableFlights)
        // TODO: 6. Retornar new Individual(childStops, fitness(childStops, shipment))
        return parentA;
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
        // TODO: 1. if (stops.size() <= 2) solo aplicar swap de vuelo
        // TODO: 2. Elegir tipo de mutación con distribución: 50% swap, 30% eliminación, 20% inserción
        // TODO: 3. SWAP: buscar en availableFlights otro vuelo con mismos aeropuertos
        //          pero menor currentLoad → reemplazar en la parada intermedia
        // TODO: 4. ELIMINACIÓN: comprobar si existe vuelo origin[i-1]→origin[i+1]
        //          con capacidad → eliminar parada i y actualizar stopOrder
        // TODO: 5. INSERCIÓN: encontrar hub h tal que existan vuelos origin[i]→h y h→origin[i+1]
        //          con capacidad → insertar parada h y reordenar stopOrder
        // TODO: 6. Recalcular fitness del individuo mutado
        return individual;
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

        // TODO: 1. Calcular tiempo total de ruta:
        //    transitHours = stops.stream()
        //        .filter(s -> s.getFlight() != null)
        //        .mapToDouble(s -> s.getFlight().getTransitTimeDays() * 24)
        //        .sum();
        //    estimatedArrival = stops.get(0).getScheduledArrival().plusHours((long) transitHours);

        // TODO: 2. Penalización por deadline:
        //    if (estimatedArrival.isAfter(shipment.getDeadline()))
        //        score -= 500;

        // TODO: 3. Penalización por saturación de aeropuertos en la ruta:
        //    for (TravelStop stop : stops) {
        //        double pct = stop.getAirport().getOccupancyPct();
        //        if (pct > warningThresholdPct)
        //            score -= 100 * (pct - warningThresholdPct);
        //    }

        // TODO: 4. Penalización por vuelos sobrecargados:
        //    for (TravelStop stop : stops) {
        //        if (stop.getFlight() == null) continue;
        //        double loadPct = stop.getFlight().getLoadPct();
        //        if (loadPct > 80) score -= 50 * (loadPct - 80);
        //    }

        // TODO: 5. Penalización por número de paradas:
        //    score -= 20 * Math.max(0, stops.size() - 2);

        return score;
    }
}
