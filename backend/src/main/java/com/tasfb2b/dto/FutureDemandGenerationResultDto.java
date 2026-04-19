package com.tasfb2b.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FutureDemandGenerationResultDto(
        LocalDate historicalFrom,
        LocalDate historicalTo,
        LocalDate projectionStart,
        LocalDate projectionEnd,
        int generatedRows,
        int deletedRows,
        int noisePct,
        int randomSeed,
        boolean projectedDemandReady,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
