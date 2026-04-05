package com.tasfb2b.dto;

public record SlaBreakdownRowDto(
        String dimension,
        String group,
        long total,
        long onTime,
        double onTimePct
) {
}
