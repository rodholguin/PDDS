package com.tasfb2b.dto;

import java.util.List;

public record AlgorithmRaceReportDto(
        String winner,
        String scenario,
        String from,
        String to,
        long generatedAtEpochMillis,
        List<BenchmarkMetricsDto> metrics,
        List<String> notes
) {
}
