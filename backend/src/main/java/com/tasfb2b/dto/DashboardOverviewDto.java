package com.tasfb2b.dto;

/**
 * Resumen operacional extendido para el panel principal.
 */
public record DashboardOverviewDto(
        long totalActiveFlights,
        long nextScheduledFlights,
        long shipmentsInRoute,
        long totalShipmentsToday,
        long inTransitToday,
        long deliveredToday,
        double slaCompliancePct,
        double slaDeltaVsPreviousPct,
        long unresolvedAlerts,
        long overdueShipments,
        long atRiskShipments,
        long stalledShipments,
        long activeIntraShipments,
        long activeInterShipments,
        double activeIntraPct,
        double activeInterPct,
        double availableNodesPct,
        double avgDeliveryHours,
        double avgCommittedHours,
        double avgDeliveryDeltaHours,
        long replanningsToday
) {
}
