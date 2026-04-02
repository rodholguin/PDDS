package com.tasfb2b.dto;

/**
 * Distribución de aeropuertos por semáforo de ocupación.
 * Respuesta de GET /api/dashboard/system-status.
 */
public record SystemStatusDto(
        long totalAirports,
        long normalAirports,
        long alertaAirports,
        long criticoAirports,
        double avgOccupancyPct,
        long totalFlights,
        long scheduledFlights,
        long inFlightFlights
) {}
