package com.tasfb2b.dto;

import java.time.LocalDateTime;

public record DemandGenerationResultDto(
        String scenario,
        int requested,
        int created,
        int failed,
        int seed,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {}
