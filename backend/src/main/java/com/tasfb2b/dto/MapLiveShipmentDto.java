package com.tasfb2b.dto;

public record MapLiveShipmentDto(
        Long shipmentId,
        String shipmentCode,
        String originIcao,
        String destinationIcao,
        Double currentLatitude,
        Double currentLongitude,
        Double nextLatitude,
        Double nextLongitude,
        double progressPct,
        Double originLatitude,
        Double originLongitude
) {
}
