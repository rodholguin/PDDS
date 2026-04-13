package com.tasfb2b.dto;

public record DatasetStatusDto(
        long totalShipments,
        long pendingShipments,
        long inRouteShipments,
        long deliveredShipments,
        long delayedShipments,
        long criticalShipments
) {
}
