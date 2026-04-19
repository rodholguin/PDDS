package com.tasfb2b.dto;

import com.tasfb2b.model.ShipmentStatus;

import java.time.LocalDateTime;
import java.util.Map;

public record ShipmentUpcomingDto(
        Long id,
        String shipmentCode,
        String airlineName,
        Map<String, Object> originAirport,
        Map<String, Object> destinationAirport,
        Integer luggageCount,
        LocalDateTime registrationDate,
        LocalDateTime deadline,
        LocalDateTime deliveredAt,
        ShipmentStatus status,
        Double progressPercentage,
        Boolean isInterContinental,
        Long nextFlightId,
        String nextFlightCode,
        LocalDateTime nextFlightDeparture
) {
}
