package com.tasfb2b.dto;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.SimulationScenario;

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
        AlgorithmType primaryAlgorithm,
        AlgorithmType secondaryAlgorithm,
        boolean running,
        boolean paused,
        int speed,
        long replannings,
        long injectedEvents,
        LocalDateTime startedAt,
        LocalDateTime lastTickAt,
        LocalDateTime updatedAt
) {
}
