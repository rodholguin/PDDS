package com.tasfb2b.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Evento manual inyectado durante la simulación.
 */
public record SimulationEventDto(
        @NotBlank(message = "El tipo de evento es obligatorio")
        String type,

        Long flightId,
        Long shipmentId,

        @Min(value = 1, message = "eventValue debe ser >= 1")
        Integer eventValue,

        String note
) {
}
