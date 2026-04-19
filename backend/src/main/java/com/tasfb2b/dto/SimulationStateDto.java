package com.tasfb2b.dto;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.SimulationScenario;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Estado operativo actual de simulación para la UI.
 */
public record SimulationStateDto(
        Long id,
        SimulationScenario scenario,
        int simulationDays,
        int executionMinutes,
        int initialVolumeAvg,
        int initialVolumeVariance,
        int flightFrequencyMultiplier,
        int cancellationRatePct,
        int intraNodeCapacity,
        int interNodeCapacity,
        int normalThresholdPct,
        int warningThresholdPct,
        LocalDateTime scenarioStartAt,
        LocalDateTime requestedScenarioStartAt,
        LocalDateTime effectiveScenarioStartAt,
        boolean dateAdjusted,
        String dateAdjustmentReason,
        boolean projectedDemandReady,
        LocalDate projectedHistoricalFrom,
        LocalDate projectedHistoricalTo,
        LocalDate projectedFrom,
        LocalDate projectedTo,
        LocalDateTime projectedGeneratedAt,
        AlgorithmType primaryAlgorithm,
        AlgorithmType secondaryAlgorithm,
        boolean running,
        boolean paused,
        int speed,
        SimulationTimeModeDto timeMode,
        long simulationSecondsPerTick,
        long effectiveSpeed,
        long replannings,
        long injectedEvents,
        LocalDateTime startedAt,
        LocalDateTime simulatedNow,
        LocalDateTime lastTickAt,
        LocalDateTime updatedAt
) {
}
