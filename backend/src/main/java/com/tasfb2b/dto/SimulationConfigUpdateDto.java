package com.tasfb2b.dto;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.SimulationScenario;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Cuerpo de POST /api/simulation/configure.
 * Solo expone los campos modificables por el usuario;
 * id, isRunning y startedAt los gestiona el servidor.
 */
public record SimulationConfigUpdateDto(

        SimulationScenario scenario,

        @Min(1) @Max(30)
        Integer simulationDays,

        @Min(10) @Max(180)
        Integer executionMinutes,

        @Min(1) @Max(500)
        Integer initialVolumeAvg,

        @Min(0) @Max(300)
        Integer initialVolumeVariance,

        @Min(1) @Max(10)
        Integer flightFrequencyMultiplier,

        @Min(0) @Max(100)
        Integer cancellationRatePct,

        @Min(200) @Max(5000)
        Integer intraNodeCapacity,

        @Min(200) @Max(5000)
        Integer interNodeCapacity,

        @Min(50) @Max(99)
        Integer normalThresholdPct,

        @Min(51) @Max(100)
        Integer warningThresholdPct,

        AlgorithmType primaryAlgorithm,

        AlgorithmType secondaryAlgorithm
) {}
