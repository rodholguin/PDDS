package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "travel_stop",
    indexes = {
        @Index(name = "idx_travel_stop_shipment", columnList = "shipment_id"),
        @Index(name = "idx_travel_stop_flight",   columnList = "flight_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TravelStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Envío al que pertenece esta parada. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /** Aeropuerto en esta parada (puede ser origen, escala o destino). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "airport_id", nullable = false)
    private Airport airport;

    /**
     * Vuelo que lleva las maletas a este aeropuerto.
     * {@code null} para la parada de origen (primer nodo de la ruta).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    /**
     * Posición dentro de la ruta del envío (0 = origen, 1 = primera escala, …).
     */
    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Column(name = "scheduled_arrival")
    private LocalDateTime scheduledArrival;

    /** Rellenado por el simulador cuando la parada se completa. */
    @Column(name = "actual_arrival")
    private LocalDateTime actualArrival;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_status", nullable = false, length = 20)
    @Builder.Default
    private StopStatus stopStatus = StopStatus.PENDING;
}
