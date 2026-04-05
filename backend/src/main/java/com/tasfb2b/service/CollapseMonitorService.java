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
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

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

        double avgLoad = computeSystemLoad();
        List<Airport> bottlenecks = getBottleneckAirports();
        double hoursLeft = estimateTimeToCollapse();

        log.info("[CollapseMonitor] Carga promedio: {:.1f}% | Cuellos de botella: {} | "
               + "Tiempo estimado al colapso: {:.1f}h",
               avgLoad, bottlenecks.size(), hoursLeft);

        if (!bottlenecks.isEmpty()) {
            bottlenecks.forEach(a -> log.warn(
                "[CollapseMonitor] ALERTA — {} ({}) al {:.1f}% de capacidad",
                a.getIcaoCode(), a.getCity(), a.getOccupancyPct()));
        }

        if (hoursLeft < 2.0) {
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
        List<Airport> airports = airportRepository.findAll();
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
        int warning = config.getWarningThresholdPct();

        return airportRepository.findAll().stream()
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
        List<Airport> airports = airportRepository.findAll();
        if (airports.isEmpty()) return Double.MAX_VALUE;

        // Capacidad libre total (maletas)
        long freeCapacity = airports.stream()
                .mapToLong(a -> Math.max(0L, a.getMaxStorageCapacity() - a.getCurrentStorageLoad()))
                .sum();

        if (freeCapacity <= 0) return 0.0; // ya colapsado

        // Maletas en tránsito o pendientes de asignación
        List<Shipment> activeShipments = shipmentRepository.findActiveShipments();
        long pendingLuggage = activeShipments.stream()
                .mapToLong(Shipment::getLuggageCount)
                .sum();

        if (pendingLuggage == 0) return Double.MAX_VALUE;

        // Tasa de llegada: asumimos que todas las maletas pendientes
        // llegarán distribuidas en las próximas 24 horas
        double arrivalsPerHour = pendingLuggage / 24.0;

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
        double avgLoad       = computeSystemLoad();
        List<Airport> necks  = getBottleneckAirports();
        double hoursLeft     = estimateTimeToCollapse();
        boolean collapsed    = avgLoad >= 100.0 || necks.size() == airportRepository.count();

        return java.util.Map.of(
            "avgSystemLoadPct",   avgLoad,
            "bottleneckCount",    necks.size(),
            "bottleneckIcaoCodes", necks.stream().map(Airport::getIcaoCode).toList(),
            "estimatedHoursToCollapse", hoursLeft == Double.MAX_VALUE ? -1 : hoursLeft,
            "isCollapsed",        collapsed,
            "snapshotAt",         LocalDateTime.now()
        );
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private SimulationConfig getConfig() {
        return configRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> SimulationConfig.builder().build());
    }
}
