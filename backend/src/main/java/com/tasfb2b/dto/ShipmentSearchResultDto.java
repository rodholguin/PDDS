package com.tasfb2b.dto;

import com.tasfb2b.model.ShipmentStatus;

/**
 * Resultado de búsqueda por código con datos de monitoreo rápido.
 */
public record ShipmentSearchResultDto(
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
        String currentNode,
        Double currentLatitude,
        Double currentLongitude,
        String remainingTime,
        double progressPct,
        boolean atRisk
) {
}
