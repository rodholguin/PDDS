package com.tasfb2b.dto;

import com.tasfb2b.model.AirportStatus;

import java.util.List;

/**
 * Detalle operacional completo de un nodo logístico.
 */
public record NodeDetailDto(
        Long id,
        String icaoCode,
        String city,
        String country,
        String continent,
        int maxStorageCapacity,
        int currentStorageLoad,
        int availableCapacity,
        double occupancyPct,
        AirportStatus status,
        long scheduledFlights,
        long inFlightFlights,
        long storedShipments,
        long inboundShipments,
        long outboundShipments,
        List<FlightScheduleEntryDto> nextFlights
) {
}
