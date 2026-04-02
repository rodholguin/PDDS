package com.tasfb2b.dto;

/**
 * KPIs principales para el panel de control del mapa.
 * Respuesta de GET /api/dashboard/kpis.
 */
public record DashboardKpisDto(
        long totalShipments,
        long activeShipments,       // PENDING + IN_ROUTE
        long criticalShipments,     // CRITICAL + DELAYED
        long deliveredShipments,    // DELIVERED
        double systemLoadPct,       // ocupación promedio de almacenes
        double collapseRisk,        // 0.0–1.0
        int totalAirports,
        long alertaAirports,        // en ALERTA
        long criticoAirports        // en CRITICO
) {}
