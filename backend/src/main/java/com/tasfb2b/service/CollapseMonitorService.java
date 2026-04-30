package com.tasfb2b.service;

import com.tasfb2b.model.*;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

/**
 * Monitor de colapso del sistema en tiempo real.
 *
 * <p>Se ejecuta automáticamente cada 30 segundos mientras haya una
 * simulación activa ({@code SimulationConfig.isRunning = true}).
 * Calcula métricas de saturación, identifica cuellos de botella y
 * estima cuánto tiempo queda antes del colapso total.
 *
 * <h3>Definición de colapso:</h3>
 * El sistema colapsa cuando todos los aeropuertos superan el umbral
 * {@code warningThresholdPct} de manera simultánea, impidiendo que
 * cualquier nuevo envío encuentre una ruta viable.
 *
 * <h3>Modelo de estimación de tiempo al colapso:</h3>
 * <pre>
 *   horasAlColapso = (warningThreshold − avgLoadPct) / tasaCrecimientoPorHora
 *
 *   tasaCrecimientoPorHora ≈ luggagePendiente / (capacidadTotal × 24h)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollapseMonitorService {

    private volatile Snapshot cachedSnapshot = new Snapshot(0.0, List.of(), Double.MAX_VALUE, LocalDateTime.MIN);

    private final AirportRepository          airportRepository;
    private final ShipmentRepository         shipmentRepository;
    private final SimulationConfigRepository configRepository;

    // ── Tarea programada ─────────────────────────────────────────────────────

    /**
     * Punto de entrada del monitor. Se dispara cada 30 segundos.
     * Solo ejecuta el análisis pesado si la simulación está activa.
     */
    @Scheduled(fixedDelay = 30_000)
    public void monitorCycle() {
        SimulationConfig config = getConfig();
        if (!Boolean.TRUE.equals(config.getIsRunning())) return;

        List<Airport> airports = airportRepository.findAll();
        double avgLoad = computeSystemLoad(airports);
        List<Airport> bottlenecks = getBottleneckAirports(airports, config.getWarningThresholdPct());
        double hoursLeft = estimateTimeToCollapse(airports);
        cachedSnapshot = new Snapshot(avgLoad, bottlenecks, hoursLeft, LocalDateTime.now());

        log.info("[CollapseMonitor] Carga promedio: {}% | Cuellos de botella: {} | Tiempo estimado al colapso: {}h",
                formatDouble(avgLoad), bottlenecks.size(), formatHours(hoursLeft));

        if (!bottlenecks.isEmpty()) {
            bottlenecks.forEach(a -> log.warn(
                "[CollapseMonitor] ALERTA - {} ({}) al {}% de capacidad",
                a.getIcaoCode(), a.getCity(), formatDouble(a.getOccupancyPct())));
        }

        if (shouldFlagCollapseImminent(config, hoursLeft, avgLoad, bottlenecks)) {
            log.error("[CollapseMonitor] COLAPSO INMINENTE — menos de 2 horas estimadas");
            // TODO: Publicar evento de dominio CollapseImminentEvent para que
            //       RoutePlannerService active medidas de emergencia:
            //       – Rechazar nuevos envíos temporalmente
            //       – Forzar redistribución de carga entre aeropuertos con capacidad
        }
    }

    // ── Métricas públicas ────────────────────────────────────────────────────

    /**
     * Calcula la carga promedio del sistema como porcentaje de ocupación
     * sobre todos los aeropuertos registrados.
     *
     * <p>Fórmula:
     * <pre>
     *   avgLoad = (Σ currentStorageLoad_i / Σ maxStorageCapacity_i) × 100
     * </pre>
     * Usar la suma global (no el promedio de porcentajes individuales)
     * evita que aeropuertos pequeños distorsionen el resultado.
     *
     * @return porcentaje de ocupación global [0.0, 100.0]
     */
    public double computeSystemLoad() {
        return computeSystemLoad(airportRepository.findAll());
    }

    private double computeSystemLoad(List<Airport> airports) {
        if (airports.isEmpty()) return 0.0;

        long totalLoad     = airports.stream().mapToLong(Airport::getCurrentStorageLoad).sum();
        long totalCapacity = airports.stream().mapToLong(Airport::getMaxStorageCapacity).sum();

        if (totalCapacity == 0) return 0.0;
        return (double) totalLoad / totalCapacity * 100.0;
    }

    /**
     * Retorna los aeropuertos cuya ocupación supera el umbral de alerta
     * ({@code warningThresholdPct}) de la configuración activa.
     *
     * <p>Estos aeropuertos son los candidatos a colapsar primero y deben ser
     * evitados por el planificador de rutas al asignar nuevos envíos.
     *
     * @return lista de aeropuertos en estado ALERTA o CRITICO, ordenada por
     *         ocupación descendente
     */
    public List<Airport> getBottleneckAirports() {
        SimulationConfig config = getConfig();
        return getBottleneckAirports(airportRepository.findAll(), config.getWarningThresholdPct());
    }

    private List<Airport> getBottleneckAirports(List<Airport> airports, int warning) {
        return airports.stream()
                .filter(a -> a.getOccupancyPct() >= warning)
                .sorted((a, b) -> Double.compare(b.getOccupancyPct(), a.getOccupancyPct()))
                .toList();
    }

    /**
     * Estima cuántas horas faltan para que el sistema colapse completamente,
     * asumiendo que la tasa de crecimiento de carga actual se mantiene constante.
     *
     * <p>Modelo lineal de estimación:
     * <pre>
     *   maletas pendientes = Σ luggageCount de envíos PENDING + IN_ROUTE
     *   capacidad libre    = Σ (maxStorageCapacity − currentStorageLoad) de todos los aeropuertos
     *   tasa maletas/hora  = maletas pendientes / 24h  (supone distribución uniforme en 24h)
     *
     *   horasAlColapso     = capacidadLibre / tasaMaletasPorHora
     * </pre>
     *
     * <p>Limitaciones del modelo:
     * <ul>
     *   <li>No considera la eliminación de maletas al ser entregadas (optimista).</li>
     *   <li>Asume distribución uniforme de llegadas (puede subestimar picos).</li>
     *   <li>Para mayor precisión: implementar modelo con arrival rate estimado
     *       a partir del historial de simulaciones anteriores.</li>
     * </ul>
     *
     * @return horas estimadas al colapso; {@code Double.MAX_VALUE} si el sistema
     *         está estable (sin envíos activos o capacidad sobrada)
     */
    public Double estimateTimeToCollapse() {
        return estimateTimeToCollapse(airportRepository.findAll());
    }

    private Double estimateTimeToCollapse(List<Airport> airports) {
        if (airports.isEmpty()) return Double.MAX_VALUE;

        SimulationConfig config = getConfig();

        // Capacidad libre total (maletas)
        long freeCapacity = airports.stream()
                .mapToLong(a -> Math.max(0L, a.getMaxStorageCapacity() - a.getCurrentStorageLoad()))
                .sum();

        if (freeCapacity <= 0) return 0.0; // ya colapsado

        long storedLuggage = airports.stream()
                .mapToLong(a -> Math.max(0, a.getCurrentStorageLoad()))
                .sum();
        long inRouteShipments = shipmentRepository.countInRoute();

        // Si no hay presión real en nodos ni muchos envíos activos en vuelo, el sistema está estable.
        if (storedLuggage == 0 && inRouteShipments == 0) {
            return Double.MAX_VALUE;
        }

        long simulationDays = config == null || config.getSimulationDays() == null ? 5 : Math.max(1, config.getSimulationDays());
        double horizonHours = Math.max(6.0, simulationDays * 24.0);

        // Aproximamos presión futura usando principalmente el stock ya almacenado en nodos;
        // los envíos actualmente en ruta aportan una fracción menor porque aún no presionan almacenes.
        double effectiveQueuedLuggage = storedLuggage + (inRouteShipments * 0.35);

        if (effectiveQueuedLuggage <= 0.0) {
            return Double.MAX_VALUE;
        }

        double arrivalsPerHour = effectiveQueuedLuggage / horizonHours;

        if (arrivalsPerHour <= 0.0) {
            return Double.MAX_VALUE;
        }

        double hoursToCollapse = freeCapacity / arrivalsPerHour;

        // TODO: Refinar con datos reales de arrival rate de simulaciones previas:
        //   1. Calcular la tasa media de los últimos N ciclos del monitor
        //   2. Aplicar un factor de seguridad (ej: ×1.2) para ser conservador
        //   3. Considerar patrón diurno: más vuelos en horas pico

        return hoursToCollapse;
    }

    /**
     * Retorna un snapshot completo del estado del sistema para el dashboard.
     * Agrega todas las métricas en un solo objeto para reducir llamadas al backend.
     *
     * TODO: Crear DTO SystemStatusSnapshot y exponer este método desde un Controller.
     *
     * @return mapa con claves: avgLoad, bottlenecks, hoursToCollapse, isCollapsed
     */
    public java.util.Map<String, Object> getSystemSnapshot() {
        Snapshot snapshot = snapshot();
        double avgLoad = snapshot.avgLoadPct();
        List<Airport> necks = snapshot.bottlenecks();
        double hoursLeft = snapshot.hoursToCollapse();
        long airportCount = airportRepository.count();
        boolean collapsed = avgLoad >= 100.0 || (airportCount > 0 && necks.size() == airportCount);

        return java.util.Map.of(
            "avgSystemLoadPct",   avgLoad,
            "bottleneckCount",    necks.size(),
            "bottleneckIcaoCodes", necks.stream().map(Airport::getIcaoCode).toList(),
            "estimatedHoursToCollapse", hoursLeft == Double.MAX_VALUE ? -1 : hoursLeft,
            "isCollapsed",        collapsed,
            "snapshotAt",         LocalDateTime.now()
        );
    }

    public Snapshot snapshot() {
        Snapshot current = cachedSnapshot;
        if (Duration.between(current.computedAt(), LocalDateTime.now()).getSeconds() > 20) {
            List<Airport> airports = airportRepository.findAll();
            SimulationConfig config = getConfig();
            current = new Snapshot(
                    computeSystemLoad(airports),
                    getBottleneckAirports(airports, config.getWarningThresholdPct()),
                    estimateTimeToCollapse(airports),
                    LocalDateTime.now()
            );
            cachedSnapshot = current;
        }
        return current;
    }

    private boolean shouldFlagCollapseImminent(SimulationConfig config,
                                               double hoursLeft,
                                               double avgLoad,
                                               List<Airport> bottlenecks) {
        if (hoursLeft >= 2.0) {
            return false;
        }
        if (avgLoad < 70.0 && bottlenecks.isEmpty()) {
            return false;
        }
        LocalDateTime startedAt = config == null ? null : config.getStartedAt();
        return startedAt == null || Duration.between(startedAt, LocalDateTime.now()).toMinutes() >= 5;
    }

    private String formatDouble(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private String formatHours(double hours) {
        if (hours == Double.MAX_VALUE) {
            return "stable";
        }
        return formatDouble(hours);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private SimulationConfig getConfig() {
        SimulationConfig config = configRepository.findTopByOrderByIdAsc();
        return config == null ? SimulationConfig.builder().build() : config;
    }

    public record Snapshot(double avgLoadPct, List<Airport> bottlenecks, double hoursToCollapse, LocalDateTime computedAt) {
    }
}
