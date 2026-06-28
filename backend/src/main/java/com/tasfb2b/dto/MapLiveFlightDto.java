package com.tasfb2b.dto;

import java.time.LocalDateTime;

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
        LocalDateTime scheduledDeparture,
        LocalDateTime scheduledArrival,
        double loadPct,
        String status
) {
}
