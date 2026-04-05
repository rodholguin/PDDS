package com.tasfb2b.dto;

import java.time.LocalDateTime;

/**
 * Fila de agenda de vuelos para un nodo y fecha.
 */
public record FlightScheduleEntryDto(
        String flightCode,
        String originIcao,
        String destinationIcao,
        LocalDateTime departure,
        LocalDateTime arrival,
        int maxCapacity,
        int currentLoad,
        int availableCapacity,
        boolean intercontinental
) {
}
