package com.tasfb2b.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record FutureDemandGenerationRequestDto(
        LocalDate historicalFrom,
        LocalDate historicalTo,
        LocalDate projectionStart,
        LocalDate projectionEnd,
        @Min(1)
        @Max(365)
        Integer randomSeed,
        @Min(0)
        @Max(60)
        Integer randomNoisePct
) {
}
