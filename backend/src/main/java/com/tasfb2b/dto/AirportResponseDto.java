package com.tasfb2b.dto;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.AirportStatus;
import com.tasfb2b.model.Continent;

/**
 * Proyección de Airport con campos calculados (ocupación, semáforo).
 * Se usa tanto en el listado como en el detalle.
 */
public record AirportResponseDto(
        Long id,
        String icaoCode,
        String city,
        String country,
        Double latitude,
        Double longitude,
        Continent continent,
        Integer maxStorageCapacity,
        Integer currentStorageLoad,
        Double occupancyPct,
        AirportStatus status
) {
    /** Factory method que resuelve los campos calculados usando los umbrales dados. */
    public static AirportResponseDto from(Airport a, int normalPct, int warningPct) {
        return new AirportResponseDto(
                a.getId(),
                a.getIcaoCode(),
                a.getCity(),
                a.getCountry(),
                a.getLatitude(),
                a.getLongitude(),
                a.getContinent(),
                a.getMaxStorageCapacity(),
                a.getCurrentStorageLoad(),
                a.getOccupancyPct(),
                a.getStatus(normalPct, warningPct)
        );
    }

    /** Sobrecarga con umbrales por defecto (70 / 90). */
    public static AirportResponseDto from(Airport a) {
        return from(a, 70, 90);
    }
}
