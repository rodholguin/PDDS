package com.tasfb2b.controller;

import com.tasfb2b.dto.SlaReportDto;
import com.tasfb2b.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Reportes", description = "Reportes operacionales")
public class ReportsController {

    private final ReportingService reportingService;

    @GetMapping("/sla-compliance")
    @Operation(summary = "Reporte de cumplimiento SLA por tipo de ruta, cliente y destino")
    public ResponseEntity<SlaReportDto> slaCompliance(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        LocalDate fromDate = (from == null || from.isBlank()) ? null : LocalDate.parse(from);
        LocalDate toDate = (to == null || to.isBlank()) ? null : LocalDate.parse(to);
        return ResponseEntity.ok(reportingService.slaReport(fromDate, toDate));
    }
}
