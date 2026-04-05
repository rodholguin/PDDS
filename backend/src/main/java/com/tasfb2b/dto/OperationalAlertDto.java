package com.tasfb2b.dto;

import java.time.LocalDateTime;

public record OperationalAlertDto(
        Long id,
        Long shipmentId,
        String shipmentCode,
        String type,
        String status,
        String note,
        String resolvedBy,
        LocalDateTime resolvedAt,
        String resolutionNote
) {
}
