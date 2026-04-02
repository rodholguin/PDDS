package com.tasfb2b.model;

public enum AirportStatus {
    /** Carga < normalThresholdPct */
    NORMAL,
    /** normalThresholdPct ≤ carga ≤ warningThresholdPct */
    ALERTA,
    /** Carga > warningThresholdPct */
    CRITICO
}
