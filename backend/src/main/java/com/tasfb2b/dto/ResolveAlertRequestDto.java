package com.tasfb2b.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveAlertRequestDto(
        @NotBlank(message = "El usuario es obligatorio")
        String user,
        @NotBlank(message = "La nota de resolucion es obligatoria")
        String note
) {
}
