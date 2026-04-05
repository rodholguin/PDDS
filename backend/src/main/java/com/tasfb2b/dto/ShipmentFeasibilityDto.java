package com.tasfb2b.dto;

public record ShipmentFeasibilityDto(
        boolean feasible,
        String message,
        String algorithm,
        int candidateRoutes
) {
}
