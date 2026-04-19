package com.tasfb2b.dto;

public record MapLiveFlightDto(
        Long flightId,
        String flightCode,
        String originIcao,
        String destinationIcao,
        Double currentLatitude,
        Double currentLongitude,
        Double originLatitude,
        Double originLongitude,
        Double destinationLatitude,
        Double destinationLongitude,
        double loadPct
) {
}
