package com.tasfb2b.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record DemandGenerationRequestDto(
        @NotBlank String scenario,
        @Min(1) @Max(200000) Integer size,
        @Min(1) Integer seed,
        @Min(1) Integer startHour,
        String algorithmName
) {}
