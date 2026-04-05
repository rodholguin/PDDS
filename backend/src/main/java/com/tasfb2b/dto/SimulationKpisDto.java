package com.tasfb2b.dto;

/**
 * KPIs de salida de simulación para escenario activo.
 */
public record SimulationKpisDto(
        double deliveredOnTimePct,
        double avgFlightOccupancyPct,
        double avgNodeOccupancyPct,
        long replannings,
        long delivered,
        long delayed,
        long active,
        long critical,
        long simulatedEvents
) {
}
