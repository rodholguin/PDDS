package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "flight")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_code", nullable = false, unique = true, length = 20)
    private String flightCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_airport_id", nullable = false)
    private Airport originAirport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_airport_id", nullable = false)
    private Airport destinationAirport;

    /**
     * Calculado: {@code true} cuando origen y destino están en continentes distintos.
     * Persistido para facilitar consultas JPA sin JOIN en memoria.
     */
    @Column(name = "is_inter_continental", nullable = false)
    @Builder.Default
    private Boolean isInterContinental = false;

    /**
     * Capacidad máxima de maletas:
     * – 150–250 para vuelos intra-continentales
     * – 150–400 para vuelos inter-continentales
     */
    @Column(nullable = false)
    private Integer maxCapacity;

    /** Maletas actualmente asignadas a este vuelo. */
    @Column(nullable = false)
    @Builder.Default
    private Integer currentLoad = 0;

    @Column(nullable = false)
    private LocalDateTime scheduledDeparture;

    @Column(nullable = false)
    private LocalDateTime scheduledArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FlightStatus status = FlightStatus.SCHEDULED;

    /**
     * Tiempo de tránsito en días:
     * – 0.5 para vuelos intra-continentales
     * – 1.0 para vuelos inter-continentales
     */
    @Column(name = "transit_time_days", nullable = false)
    private Double transitTimeDays;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Recalcula {@code isInterContinental} y {@code transitTimeDays}
     * cada vez que la entidad se persiste o actualiza.
     */
    @PrePersist
    @PreUpdate
    private void computeDerivedFields() {
        if (originAirport != null && destinationAirport != null) {
            this.isInterContinental =
                    originAirport.getContinent() != destinationAirport.getContinent();
        }
        this.transitTimeDays = Boolean.TRUE.equals(isInterContinental) ? 1.0 : 0.5;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Transient
    public int getAvailableCapacity() {
        return (maxCapacity == null ? 0 : maxCapacity) - currentLoad;
    }

    @Transient
    public double getLoadPct() {
        if (maxCapacity == null || maxCapacity == 0) return 0.0;
        return (double) currentLoad / maxCapacity * 100.0;
    }
}
