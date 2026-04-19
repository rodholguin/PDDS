package com.tasfb2b.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DatasetStatusDto(
        long totalShipments,
        long pendingShipments,
        long inRouteShipments,
        long deliveredShipments,
        long delayedShipments,
        long criticalShipments,
        boolean projectedDemandReady,
        LocalDate projectedHistoricalFrom,
        LocalDate projectedHistoricalTo,
        LocalDate projectedFrom,
        LocalDate projectedTo,
        LocalDateTime projectedGeneratedAt
) {
}
