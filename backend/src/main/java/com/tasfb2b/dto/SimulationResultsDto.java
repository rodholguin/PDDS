package com.tasfb2b.dto;

import com.tasfb2b.service.algorithm.OptimizationResult;

import java.util.Map;

/**
 * Respuesta enriquecida de resultados de simulación.
 */
public record SimulationResultsDto(
        Map<String, OptimizationResult> algorithms,
        SimulationKpisDto kpis,
        String benchmarkWinner
) {
}
