package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "airport")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Airport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código ICAO único del aeropuerto (ej: KJFK, EGLL). */
    @Column(name = "icao_code", nullable = false, unique = true, length = 10)
    private String icaoCode;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Continent continent;

    /**
     * Capacidad máxima de almacenamiento (500–800 maletas según enunciado).
     */
    @Column(nullable = false)
    private Integer maxStorageCapacity;

    /**
     * Maletas actualmente almacenadas en el aeropuerto.
     * Se actualiza cada vez que un envío pasa por este nodo.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer currentStorageLoad = 0;

    // ── Semáforo de ocupación ────────────────────────────────────────────────

    /**
     * Estado calculado con los umbrales por defecto (70% / 90%).
     * Para usar los umbrales de {@code SimulationConfig}, llamar a
     * {@link #getStatus(int, int)}.
     */
    @Transient
    public AirportStatus getStatus() {
        return getStatus(70, 90);
    }

    /**
     * Estado calculado con umbrales configurables.
     *
     * @param normalPct  porcentaje hasta el que el estado es NORMAL  (ej. 70)
     * @param warningPct porcentaje hasta el que el estado es ALERTA  (ej. 90)
     */
    public AirportStatus getStatus(int normalPct, int warningPct) {
        if (maxStorageCapacity == null || maxStorageCapacity == 0) return AirportStatus.NORMAL;
        double pct = (double) currentStorageLoad / maxStorageCapacity * 100.0;
        if (pct > warningPct)  return AirportStatus.CRITICO;
        if (pct >= normalPct)  return AirportStatus.ALERTA;
        return AirportStatus.NORMAL;
    }

    /** Porcentaje de ocupación (0–100). */
    @Transient
    public double getOccupancyPct() {
        if (maxStorageCapacity == null || maxStorageCapacity == 0) return 0.0;
        return (double) currentStorageLoad / maxStorageCapacity * 100.0;
    }

    /** Espacio disponible en maletas. */
    @Transient
    public int getAvailableCapacity() {
        return (maxStorageCapacity == null ? 0 : maxStorageCapacity) - currentStorageLoad;
    }
}
