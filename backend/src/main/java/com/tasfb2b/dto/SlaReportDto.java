package com.tasfb2b.dto;

import java.time.LocalDate;
import java.util.List;

public record SlaReportDto(
        LocalDate from,
        LocalDate to,
        List<SlaBreakdownRowDto> rows
) {
}
