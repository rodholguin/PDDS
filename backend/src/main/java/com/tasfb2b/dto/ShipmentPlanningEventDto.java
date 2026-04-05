package com.tasfb2b.dto;

import java.time.LocalDateTime;

public record ShipmentPlanningEventDto(
        String eventType,
        LocalDateTime eventAt,
        String reason,
        String algorithm,
        String route
) {
}
