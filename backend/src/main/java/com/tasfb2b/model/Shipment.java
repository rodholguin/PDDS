package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "shipment",
        indexes = {
                @Index(name = "idx_shipment_status", columnList = "status"),
                @Index(name = "idx_shipment_registration_date", columnList = "registration_date"),
                @Index(name = "idx_shipment_deadline", columnList = "deadline"),
                @Index(name = "idx_shipment_origin", columnList = "origin_airport_id"),
                @Index(name = "idx_shipment_destination", columnList = "destination_airport_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Código único del envío, generado por el servicio (ej: ENV-2025-001).
     */
    @Column(name = "shipment_code", nullable = false, unique = true, length = 30)
    private String shipmentCode;

    @Column(name = "airline_name", nullable = false, length = 100)
    private String airlineName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_airport_id", nullable = false)
    private Airport originAirport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_airport_id", nullable = false)
    private Airport destinationAirport;

    /** Número de maletas en este envío. */
    @Column(name = "luggage_count", nullable = false)
    private Integer luggageCount;

    @Column(name = "registration_date", nullable = false)
    private LocalDateTime registrationDate;

    /**
     * Calculado en {@code @PrePersist/@PreUpdate}:
     * +1 día (intra-continental) o +2 días (inter-continental) desde registrationDate.
     */
    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShipmentStatus status = ShipmentStatus.PENDING;

    /** Timestamp de confirmacion de entrega final. */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /**
     * Porcentaje de avance calculado por el servicio de simulación
     * a partir de las paradas completadas vs. totales.
     */
    @Column(name = "progress_percentage", nullable = false)
    @Builder.Default
    private Double progressPercentage = 0.0;

    /**
     * Calculado: {@code true} cuando origen y destino están en continentes distintos.
     */
    @Column(name = "is_inter_continental", nullable = false)
    @Builder.Default
    private Boolean isInterContinental = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @PrePersist
    @PreUpdate
    private void computeDerivedFields() {
        if (originAirport != null && destinationAirport != null) {
            this.isInterContinental =
                    originAirport.getContinent() != destinationAirport.getContinent();
        }
        if (registrationDate != null) {
            long extraDays = Boolean.TRUE.equals(isInterContinental) ? 2L : 1L;
            this.deadline = registrationDate.plusDays(extraDays);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Transient
    public boolean isOverdue() {
        return deadline != null
                && LocalDateTime.now().isAfter(deadline)
                && status != ShipmentStatus.DELIVERED;
    }

    @Transient
    public boolean isDeliveredOnTime() {
        return status == ShipmentStatus.DELIVERED
                && deliveredAt != null
                && deadline != null
                && !deliveredAt.isAfter(deadline);
    }
}
