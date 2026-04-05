package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Parámetros de configuración de la simulación.
 * Singleton en base de datos: siempre se usa el registro con id = 1.
 */
@Entity
@Table(name = "simulation_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private SimulationScenario scenario = SimulationScenario.DAY_TO_DAY;

    /**
     * Días simulados para el escenario PERIOD_SIMULATION (3, 5 o 7).
     */
    @Column(name = "simulation_days")
    @Builder.Default
    private Integer simulationDays = 5;

    /**
     * Tiempo de ejecución objetivo en minutos (30–90 para simulación de periodo).
     */
    @Column(name = "execution_minutes")
    @Builder.Default
    private Integer executionMinutes = 60;

    @Column(name = "initial_volume_avg")
    @Builder.Default
    private Integer initialVolumeAvg = 8;

    @Column(name = "initial_volume_variance")
    @Builder.Default
    private Integer initialVolumeVariance = 3;

    @Column(name = "flight_frequency_multiplier")
    @Builder.Default
    private Integer flightFrequencyMultiplier = 1;

    @Column(name = "cancellation_rate_pct")
    @Builder.Default
    private Integer cancellationRatePct = 5;

    @Column(name = "intra_node_capacity")
    @Builder.Default
    private Integer intraNodeCapacity = 700;

    @Column(name = "inter_node_capacity")
    @Builder.Default
    private Integer interNodeCapacity = 800;

    /**
     * Umbral (%) hasta el que el semáforo del aeropuerto es verde (NORMAL).
     * Por defecto 70.
     */
    @Column(name = "normal_threshold_pct", nullable = false)
    @Builder.Default
    private Integer normalThresholdPct = 70;

    /**
     * Umbral (%) hasta el que el semáforo es ámbar (ALERTA).
     * Por encima de este valor el estado es CRITICO.
     * Por defecto 90.
     */
    @Column(name = "warning_threshold_pct", nullable = false)
    @Builder.Default
    private Integer warningThresholdPct = 90;

    /** Algoritmo principal de asignación. */
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_algorithm", nullable = false, length = 20)
    @Builder.Default
    private AlgorithmType primaryAlgorithm = AlgorithmType.GENETIC;

    /** Algoritmo secundario / de respaldo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "secondary_algorithm", nullable = false, length = 20)
    @Builder.Default
    private AlgorithmType secondaryAlgorithm = AlgorithmType.ANT_COLONY;

    /** {@code true} mientras haya una simulación en curso. */
    @Column(name = "is_running", nullable = false)
    @Builder.Default
    private Boolean isRunning = false;

    /** Momento en que se inició la última simulación. */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
}
