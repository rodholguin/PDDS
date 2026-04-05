package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.TravelStop;

import java.time.LocalDate;
import java.util.List;

/**
 * Contrato común para todos los algoritmos de optimización de rutas.
 *
 * <h3>Estrategia anticolapso — orden de prioridades:</h3>
 * <ol>
 *   <li><b>URGENCIA</b>: maletas con deadline más próximo se asignan primero
 *       (menor slack = deadline − ahora).</li>
 *   <li><b>BALANCE DE CARGA</b>: preferir vuelos y aeropuertos con menor
 *       porcentaje de ocupación para distribuir la presión.</li>
 *   <li><b>RUTA MÁS CORTA</b>: minimizar paradas intermedias; vuelo directo
 *       o máximo 1 escala si es posible.</li>
 *   <li><b>CONTINGENCIA</b>: si el vuelo directo está lleno, buscar rutas
 *       alternativas pasando por un hub de menor carga.</li>
 *   <li><b>RECHAZO CONTROLADO</b>: si no existe ruta factible (todos los
 *       caminos saturados), marcar el envío como CRITICAL y alertar.</li>
 * </ol>
 */
public interface RouteOptimizer {

    /** Nombre descriptivo del algoritmo (usado en logs y comparativas). */
    String getAlgorithmName();

    /**
     * Planifica la ruta completa para un envío dado el estado actual del sistema.
     *
     * @param shipment         envío a planificar
     * @param availableFlights vuelos SCHEDULED con capacidad disponible
     * @param airports         todos los aeropuertos del sistema
     * @return lista de {@link TravelStop} ordenada por {@code stopOrder},
     *         vacía si no hay ruta factible (envío marcado CRITICAL por el servicio)
     */
    List<TravelStop> planRoute(Shipment shipment,
                               List<Flight> availableFlights,
                               List<Airport> airports);

    /**
     * Replanifica la ruta de un envío desde una parada fallida (vuelo cancelado
     * o aeropuerto saturado descubierto mid-route).
     *
     * @param shipment         envío a replanificar
     * @param failedStop       parada en la que la ruta original falló
     * @param availableFlights vuelos disponibles tras el fallo
     * @return nueva secuencia de paradas desde {@code failedStop.airport}
     *         hasta el destino final
     */
    List<TravelStop> replanRoute(Shipment shipment,
                                 TravelStop failedStop,
                                 List<Flight> availableFlights);

    /**
     * Evalúa si existe al menos una ruta factible sin persistir cambios.
     */
    default boolean hasFeasibleRoute(Shipment shipment,
                                     List<Flight> availableFlights,
                                     List<Airport> airports) {
        return !planRoute(shipment, availableFlights, airports).isEmpty();
    }

    /**
     * Evalúa el rendimiento del algoritmo sobre los envíos registrados
     * en un período dado y devuelve métricas agregadas.
     *
     * @param shipments envíos a evaluar
     * @param from      inicio del período (inclusive)
     * @param to        fin del período (inclusive)
     * @return {@link OptimizationResult} con métricas y momento de colapso
     */
    OptimizationResult evaluatePerformance(List<Shipment> shipments,
                                           LocalDate from,
                                           LocalDate to);
}
