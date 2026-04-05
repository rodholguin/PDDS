package com.tasfb2b.dto;

public record BenchmarkMetricsDto(
        String algorithmName,
        int sampleSize,
        double completedPct,
        double avgTransitHours,
        double p95TransitHours,
        double costPerLuggage,
        double flightUtilizationPct,
        int saturatedAirports,
        int totalReplanning,
        int collapseEvents
) {
}
