package com.tasfb2b.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Cuerpo de la petición POST /api/shipments.
 * Los campos de aeropuerto se resuelven por código ICAO.
 */
public record ShipmentCreateDto(

        @NotBlank(message = "El nombre de la aerolínea es obligatorio")
        String airlineName,

        @NotBlank(message = "El código ICAO de origen es obligatorio")
        String originIcao,

        @NotBlank(message = "El código ICAO de destino es obligatorio")
        String destinationIcao,

        @NotNull @Min(value = 1, message = "El número de maletas debe ser ≥ 1")
        Integer luggageCount,

        /** Opcional; si no se envía, se usa LocalDateTime.now(). */
        LocalDateTime registrationDate,

        /**
         * Algoritmo a usar para planificar la ruta.
         * Valores válidos: "Genetic Algorithm" o "Ant Colony Optimization".
         * Si se omite, se usa el primaryAlgorithm de SimulationConfig.
         */
        String algorithmName
) {}
