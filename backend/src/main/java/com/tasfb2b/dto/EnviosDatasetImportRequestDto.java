package com.tasfb2b.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

public record EnviosDatasetImportRequestDto(
        @Min(1) Integer seed,
        @Min(1) @Max(60) Integer maxAirports,
        @Min(1) @Max(50000) Integer maxPerAirport,
        List<String> includeOrigins,
        Boolean fullDataset,
        String algorithmName
) {}
