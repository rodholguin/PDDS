package com.tasfb2b.dto;

import com.tasfb2b.model.ShipmentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Respuesta detallada de GET /api/shipments/{id}.
 * Incluye la lista de paradas del plan de viaje.
 */
public record ShipmentDetailDto(
        Long id,
        String shipmentCode,
        String airlineName,
        String originIcaoCode,
        String originCity,
        String destinationIcaoCode,
        String destinationCity,
        Integer luggageCount,
        LocalDateTime registrationDate,
        LocalDateTime deadline,
        LocalDateTime deliveredAt,
        ShipmentStatus status,
        Double progressPercentage,
        Boolean isInterContinental,
        String lastConfirmedNode,
        ShipmentLegDto currentLeg,
        ShipmentLegDto nextLeg,
        LocalDateTime estimatedDestinationArrival,
        List<TravelStopDto> stops,
        List<ShipmentLegDto> legs,
        List<ShipmentAuditLogDto> audit
) {}
