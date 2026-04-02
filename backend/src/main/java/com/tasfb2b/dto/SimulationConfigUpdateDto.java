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

        @Min(50) @Max(99)
        Integer normalThresholdPct,

        @Min(51) @Max(100)
        Integer warningThresholdPct,

        AlgorithmType primaryAlgorithm,

        AlgorithmType secondaryAlgorithm
) {}
