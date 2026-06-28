package com.tasfb2b.dto;

import com.tasfb2b.service.algorithm.OptimizationResult;

import java.util.Map;
import java.util.Set;

/**
 * Respuesta enriquecida de resultados de simulación.
 */
public record SimulationResultsDto(
        Map<String, OptimizationResult> algorithms,
        SimulationKpisDto kpis,
        String benchmarkWinner,
        String scenario,
        String scenarioStartAt,
        String scenarioEndAt,
        String collapseDetectedAt,
        String collapseShipmentCode,
        Long collapseSurvivalSeconds,
        Long totalShipments,
        Long plannedShipments,
        Long failedPlanningShipments,
        Long activeShipments,
        Long delayedShipments,
        Long criticalShipments,
        Double avgNodeOccupancyPct,
        Long periodPlanningBacklog,
        Long lastPlanningDurationMs,
        Long replannings
) {
    public static final Set<String> ALL_FIELDS = Set.of(
            "algorithms", "kpis", "benchmarkWinner", "scenario", "scenarioStartAt",
            "scenarioEndAt", "collapseDetectedAt", "collapseShipmentCode",
            "collapseSurvivalSeconds", "totalShipments", "plannedShipments",
            "failedPlanningShipments", "activeShipments", "delayedShipments",
            "criticalShipments", "avgNodeOccupancyPct", "periodPlanningBacklog",
            "lastPlanningDurationMs", "replannings"
    );
}
