package com.tasfb2b.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Payload para cambio de velocidad de simulación.
 */
public record SimulationSpeedDto(
        @Min(value = 1, message = "La velocidad minima es 1x")
        @Max(value = 20, message = "La velocidad maxima es 20x")
        int speed
) {
}
