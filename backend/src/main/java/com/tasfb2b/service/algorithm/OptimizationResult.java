package com.tasfb2b.service.algorithm;

import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO inmutable con los métricas de rendimiento producidas por
 * un {@link RouteOptimizer} al evaluar un conjunto de envíos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationResult {

    /** Nombre del algoritmo que generó este resultado. */
    private String algorithmName;

    /** Envíos entregados antes o en el deadline. */
    private Integer completedShipments;

    /** completedShipments / totalShipments × 100. */
    private Double completedPct;

    /** Promedio de horas de tránsito de los envíos completados. */
    private Double avgTransitHours;

    /** Número de veces que se replanificó alguna ruta durante la evaluación. */
    private Integer totalReplanning;

    /**
     * Costo operacional estimado (unidad arbitraria).
     * Considera número de vuelos utilizados y paradas intermedias.
     */
    private Double operationalCost;

    /**
     * Porcentaje promedio de utilización de vuelos
     * (currentLoad / maxCapacity × 100) sobre todos los vuelos involucrados.
     */
    private Double flightUtilizationPct;

    /** Número de aeropuertos que alcanzaron estado CRITICO durante la evaluación. */
    private Integer saturatedAirports;

    /**
     * Momento en que el sistema colapsó (todos los aeropuertos CRITICO).
     * {@code null} si el colapso no ocurrió en el período evaluado.
     */
    private LocalDateTime collapseReachedAt;
}
