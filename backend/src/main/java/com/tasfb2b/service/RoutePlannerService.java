package com.tasfb2b.service;

import com.tasfb2b.model.*;
import com.tasfb2b.repository.*;
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import com.tasfb2b.service.algorithm.OptimizationResult;
import com.tasfb2b.service.algorithm.RouteOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio orquestador de planificación de rutas.
 *
 * <p>Coordina los dos algoritmos de optimización ({@link GeneticAlgorithm}
 * y {@link AntColonyOptimization}), gestiona la selección del algoritmo activo
 * según {@link SimulationConfig}, persiste los {@link TravelStop} resultantes
 * y expone métricas de riesgo de colapso.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class RoutePlannerService {

    private final GeneticAlgorithm       geneticAlgorithm;
    private final AntColonyOptimization  antColonyOptimization;

    private final AirportRepository          airportRepository;
    private final FlightRepository           flightRepository;
    private final ShipmentRepository         shipmentRepository;
    private final TravelStopRepository       travelStopRepository;
    private final SimulationConfigRepository configRepository;

    // ── Planificación ────────────────────────────────────────────────────────

    /**
     * Planifica la ruta de un envío usando el algoritmo especificado y persiste
     * los {@link TravelStop} resultantes.
     *
     * @param shipment      envío a planificar
     * @param algorithmName "Genetic Algorithm" o "Ant Colony Optimization"
     * @return lista de paradas planificadas (vacía si no hay ruta factible)
     */
    public List<TravelStop> planShipment(Shipment shipment, String algorithmName) {
        log.info("[RoutePlanner] Planificando envío {} con algoritmo '{}'",
                shipment.getShipmentCode(), algorithmName);

        RouteOptimizer optimizer = resolveOptimizer(algorithmName);
        List<Flight>   available = flightRepository.findFlightsWithAvailableCapacity();
        List<Airport>  airports  = airportRepository.findAll();

        List<TravelStop> stops = optimizer.planRoute(shipment, available, airports);

        if (stops.isEmpty()) {
            log.warn("[RoutePlanner] Sin ruta factible para {}. Marcando CRITICAL.",
                    shipment.getShipmentCode());
            shipment.setStatus(ShipmentStatus.CRITICAL);
            shipmentRepository.save(shipment);
            return stops;
        }

        // Persistir las paradas y actualizar estado del envío
        stops.forEach(stop -> stop.setShipment(shipment));
        travelStopRepository.saveAll(stops);

        shipment.setStatus(ShipmentStatus.IN_ROUTE);
        shipmentRepository.save(shipment);

        log.info("[RoutePlanner] Ruta de {} paradas creada para {}",
                stops.size(), shipment.getShipmentCode());
        return stops;
    }

    /**
     * Replanifica la ruta de un envío desde la última parada fallida.
     * Busca la parada PENDING más próxima y delega al algoritmo activo en config.
     *
     * @param shipmentId ID del envío a replanificar
     * @return nueva lista de paradas desde la parada fallida
     */
    public List<TravelStop> replanShipment(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Envío no encontrado: " + shipmentId));

        List<TravelStop> currentStops = travelStopRepository
                .findByShipmentOrderByStopOrderAsc(shipment);

        TravelStop failedStop = currentStops.stream()
                .filter(s -> s.getStopStatus() == StopStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No hay paradas PENDING en envío " + shipmentId));

        String activeAlgo = getActiveAlgorithmName();
        RouteOptimizer optimizer = resolveOptimizer(activeAlgo);
        List<Flight> available   = flightRepository.findFlightsWithAvailableCapacity();

        List<TravelStop> newStops = optimizer.replanRoute(shipment, failedStop, available);

        if (newStops.isEmpty()) {
            log.error("[RoutePlanner] Replanificación fallida para envío {}. Estado → DELAYED.",
                    shipment.getShipmentCode());
            shipment.setStatus(ShipmentStatus.DELAYED);
            shipmentRepository.save(shipment);
            return newStops;
        }

        // Eliminar paradas pendientes anteriores y persistir las nuevas
        travelStopRepository.deleteAll(
                currentStops.stream()
                        .filter(s -> s.getStopStatus() == StopStatus.PENDING)
                        .toList());
        newStops.forEach(s -> s.setShipment(shipment));
        travelStopRepository.saveAll(newStops);

        log.info("[RoutePlanner] Replanificación exitosa para {} ({} nuevas paradas)",
                shipment.getShipmentCode(), newStops.size());
        return newStops;
    }

    // ── Comparativa de algoritmos ────────────────────────────────────────────

    /**
     * Ejecuta ambos algoritmos sobre el mismo conjunto de envíos y retorna
     * los resultados para su comparación en el dashboard.
     *
     * @param shipments envíos a evaluar (misma lista para ambos)
     * @return mapa {@code algorithmName → OptimizationResult}
     */
    public Map<String, OptimizationResult> runBothAlgorithms(List<Shipment> shipments) {
        log.info("[RoutePlanner] Comparando algoritmos sobre {} envíos", shipments.size());

        Map<String, OptimizationResult> results = new HashMap<>();

        // TODO: Ejecutar en paralelo con CompletableFuture para reducir tiempo de comparativa.
        //       Por ahora, ejecución secuencial para mantener el esqueleto simple.

        OptimizationResult gaResult  = geneticAlgorithm.evaluatePerformance(
                shipments, null, null);
        OptimizationResult acoResult = antColonyOptimization.evaluatePerformance(
                shipments, null, null);

        results.put(geneticAlgorithm.getAlgorithmName(),     gaResult);
        results.put(antColonyOptimization.getAlgorithmName(), acoResult);

        log.info("[RoutePlanner] GA completedPct={:.1f}%  ACO completedPct={:.1f}%",
                gaResult.getCompletedPct(), acoResult.getCompletedPct());

        return results;
    }

    // ── Métricas de colapso ──────────────────────────────────────────────────

    /**
     * Determina si el sistema ha colapsado: más del 90 % de los aeropuertos
     * tienen estado CRITICO según los umbrales de {@link SimulationConfig}.
     *
     * @return {@code true} si el sistema está colapsado
     */
    @Transactional(readOnly = true)
    public Boolean isSystemCollapsed() {
        SimulationConfig config = getConfig();
        List<Airport> all       = airportRepository.findAll();

        if (all.isEmpty()) return false;

        long critical = all.stream()
                .filter(a -> a.getStatus(config.getNormalThresholdPct(),
                                         config.getWarningThresholdPct())
                              == AirportStatus.CRITICO)
                .count();

        double pct = (double) critical / all.size() * 100.0;
        boolean collapsed = pct > 90.0;

        if (collapsed) {
            log.error("[RoutePlanner] SISTEMA COLAPSADO — {}% de aeropuertos en CRITICO", pct);
        }
        return collapsed;
    }

    /**
     * Calcula el riesgo de colapso del sistema como un valor entre 0.0 y 1.0.
     *
     * <p>Fórmula:
     * <pre>
     *   riesgo = 0.6 × (avgAirportOccupancy / 100)
     *          + 0.3 × (fracciónAeropuertosCriticos)
     *          + 0.1 × (fracciónEnvíosDelayedOCritical / totalActivos)
     * </pre>
     *
     * Los pesos reflejan que la saturación acumulada de almacenes es el principal
     * predictor de colapso, seguido por la concentración de aeropuertos críticos
     * y la tasa de envíos problemáticos.
     *
     * @return valor en [0.0, 1.0] donde 1.0 = colapso inminente
     */
    @Transactional(readOnly = true)
    public Double getCollapseRisk() {
        SimulationConfig config = getConfig();
        List<Airport> airports  = airportRepository.findAll();

        if (airports.isEmpty()) return 0.0;

        // Componente 1: ocupación promedio de almacenes (0–1)
        double avgOccupancy = airports.stream()
                .mapToDouble(Airport::getOccupancyPct)
                .average()
                .orElse(0.0) / 100.0;

        // Componente 2: fracción de aeropuertos en CRITICO (0–1)
        long criticalCount = airports.stream()
                .filter(a -> a.getStatus(config.getNormalThresholdPct(),
                                         config.getWarningThresholdPct())
                              == AirportStatus.CRITICO)
                .count();
        double criticalFraction = (double) criticalCount / airports.size();

        // Componente 3: fracción de envíos con problemas sobre activos (0–1)
        List<Shipment> active      = shipmentRepository.findActiveShipments();
        List<Shipment> problematic = shipmentRepository.findCriticalShipments();
        double problemFraction = active.isEmpty()
                ? 0.0
                : (double) problematic.size() / (active.size() + problematic.size());

        double risk = 0.6 * avgOccupancy
                    + 0.3 * criticalFraction
                    + 0.1 * problemFraction;

        log.debug("[RoutePlanner] Riesgo colapso: {:.3f} "
                + "(avgOcc={:.1f}% criticos={} problemas={})",
                risk, avgOccupancy * 100, criticalCount, problematic.size());

        return Math.min(1.0, risk);
    }

    // ── Helpers privados ─────────────────────────────────────────────────────

    private RouteOptimizer resolveOptimizer(String algorithmName) {
        if (antColonyOptimization.getAlgorithmName().equalsIgnoreCase(algorithmName)) {
            return antColonyOptimization;
        }
        return geneticAlgorithm; // default
    }

    private String getActiveAlgorithmName() {
        return getConfig().getPrimaryAlgorithm() == AlgorithmType.ANT_COLONY
                ? antColonyOptimization.getAlgorithmName()
                : geneticAlgorithm.getAlgorithmName();
    }

    private SimulationConfig getConfig() {
        return configRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> SimulationConfig.builder().build());
    }
}
