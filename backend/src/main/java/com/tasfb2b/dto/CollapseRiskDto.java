package com.tasfb2b.dto;

import java.util.List;

/**
 * Respuesta de GET /api/simulation/collapse-risk.
 *
 * @param risk                    valor en [0.0, 1.0]; 1.0 = colapso inminente
 * @param bottlenecks             códigos ICAO de aeropuertos en ALERTA o CRITICO
 * @param estimatedHoursToCollapse horas estimadas al colapso; -1 si el sistema está estable
 * @param systemLoadPct           ocupación promedio del sistema en %
 */
public record CollapseRiskDto(
        Double risk,
        List<String> bottlenecks,
        Double estimatedHoursToCollapse,
        Double systemLoadPct
) {}
