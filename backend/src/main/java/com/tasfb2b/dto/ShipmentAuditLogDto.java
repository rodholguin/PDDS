package com.tasfb2b.dto;

import com.tasfb2b.model.ShipmentAuditType;

import java.time.LocalDateTime;

public record ShipmentAuditLogDto(
        Long id,
        ShipmentAuditType eventType,
        String message,
        LocalDateTime eventAt,
        String airportIcao,
        Double airportLatitude,
        Double airportLongitude,
        String flightCode
) {
}
