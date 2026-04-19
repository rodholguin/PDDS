package com.tasfb2b.dto;

import com.tasfb2b.model.FlightStatus;
import com.tasfb2b.model.StopStatus;

import java.time.LocalDateTime;

/** Tramo operativo de un envio entre dos aeropuertos consecutivos. */
public record ShipmentLegDto(
        Long flightId,
        String flightCode,
        String fromIcaoCode,
        String fromCity,
        String toIcaoCode,
        String toCity,
        LocalDateTime scheduledDeparture,
        LocalDateTime scheduledArrival,
        LocalDateTime actualArrival,
        FlightStatus flightStatus,
        StopStatus stopStatus,
        boolean current,
        boolean next
) {
}
