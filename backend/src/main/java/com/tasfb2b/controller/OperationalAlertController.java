package com.tasfb2b.controller;

import com.tasfb2b.dto.OperationalAlertDto;
import com.tasfb2b.dto.ResolveAlertRequestDto;
import com.tasfb2b.service.OperationalAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@Tag(name = "Alertas", description = "Ciclo de vida de alertas operacionales")
public class OperationalAlertController {

    private final OperationalAlertService alertService;

    @GetMapping
    @Operation(summary = "Listar alertas no resueltas")
    public ResponseEntity<List<OperationalAlertDto>> list() {
        return ResponseEntity.ok(alertService.activeAlerts());
    }

    @PostMapping("/create")
    @Operation(summary = "Crear alerta operacional manual")
    public ResponseEntity<OperationalAlertDto> create(
            @RequestParam Long shipmentId,
            @RequestParam String type,
            @RequestParam String note
    ) {
        return ResponseEntity.ok(alertService.createFromShipment(shipmentId, type, note));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Marcar alerta como resuelta")
    public ResponseEntity<OperationalAlertDto> resolve(
            @PathVariable Long id,
            @Valid @RequestBody ResolveAlertRequestDto request
    ) {
        return ResponseEntity.ok(alertService.resolve(id, request.user(), request.note()));
    }
}
