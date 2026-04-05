package com.tasfb2b.dto;

public record RouteNetworkEdgeDto(
        String originIcao,
        Double originLatitude,
        Double originLongitude,
        String destinationIcao,
        Double destinationLatitude,
        Double destinationLongitude,
        boolean operational,
        boolean suspended,
        long scheduledCount,
        long inFlightCount,
        long cancelledCount
) {
}
