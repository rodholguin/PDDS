package com.tasfb2b.dto;

import com.tasfb2b.model.ShipmentStatus;

/**
 * Resumen para selección en mapa y panel lateral.
 */
public record ShipmentSummaryDto(
        Long id,
        String shipmentCode,
        String airlineName,
        String originIcao,
        Double originLatitude,
        Double originLongitude,
        String destinationIcao,
        Double destinationLatitude,
        Double destinationLongitude,
        ShipmentStatus status,
        String lastVisitedNode,
        Double currentLatitude,
        Double currentLongitude,
        String remainingTime,
        double progressPct,
        boolean atRisk,
        boolean overdue,
        String criticalReason
) {
}
