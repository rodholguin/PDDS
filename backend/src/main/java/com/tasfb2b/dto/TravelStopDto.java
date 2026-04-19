package com.tasfb2b.dto;

import com.tasfb2b.model.StopStatus;

import java.time.LocalDateTime;

/** Parada individual dentro del plan de viaje de un envío. */
public record TravelStopDto(
        Long id,
        Integer stopOrder,
        String airportIcaoCode,
        String airportCity,
        Double airportLatitude,
        Double airportLongitude,
        String flightCode,
        LocalDateTime scheduledDeparture,
        LocalDateTime scheduledArrival,
        LocalDateTime actualArrival,
        StopStatus stopStatus
) {}
