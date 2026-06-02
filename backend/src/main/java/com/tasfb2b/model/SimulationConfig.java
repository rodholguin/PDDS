package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "scenario_start_at")
    private LocalDateTime scenarioStartAt;

    @Column(name = "requested_scenario_start_at")
    private LocalDateTime requestedScenarioStartAt;

    @Column(name = "effective_scenario_start_at")
    private LocalDateTime effectiveScenarioStartAt;

    @Column(name = "date_adjusted", nullable = false)
    @Builder.Default
    private Boolean dateAdjusted = false;

    @Column(name = "date_adjustment_reason", length = 255)
    private String dateAdjustmentReason;

    @Column(name = "projected_demand_ready")
    @Builder.Default
    private Boolean projectedDemandReady = false;

    @Column(name = "projected_historical_from")
    private LocalDate projectedHistoricalFrom;

    @Column(name = "projected_historical_to")
    private LocalDate projectedHistoricalTo;

    @Column(name = "projected_from")
    private LocalDate projectedFrom;

    @Column(name = "projected_to")
    private LocalDate projectedTo;

    @Column(name = "projected_generated_at")
    private LocalDateTime projectedGeneratedAt;

    @Column(name = "runtime_simulated_now")
    private LocalDateTime runtimeSimulatedNow;

    @Column(name = "runtime_last_tick_at")
    private LocalDateTime runtimeLastTickAt;

    // ── Colapso (escenario COLLAPSE_TEST) ────────────────────────────────────
    // El colapso es el instante (reloj simulado) en que el PRIMER envío no llega
    // a tiempo. Se registra una sola vez por corrida y detiene la simulación.

    /** Momento simulado del colapso = deadline del primer envío que incumple. */
    @Column(name = "collapse_detected_at_sim")
    private LocalDateTime collapseDetectedAtSim;

    /** Id del primer envío que no llegó a tiempo. */
    @Column(name = "collapse_shipment_id")
    private Long collapseShipmentId;

    /** Código del primer envío que no llegó a tiempo. */
    @Column(name = "collapse_shipment_code", length = 30)
    private String collapseShipmentCode;

    // ── Umbrales del semáforo de KPIs de riesgo ──────────────────────────────
    // Definidos en el front y persistidos aquí. Los KPIs de volumen (vuelos, envíos
    // en ruta) son informativos; el semáforo solo aplica a estos KPIs de riesgo.

    /** SLA (%): verde si cumplimiento ≥ este valor. */
    @Column(name = "sla_warn_pct")
    @Builder.Default
    private Integer slaWarnPct = 90;

    /** SLA (%): ámbar si cumplimiento ≥ este valor; rojo por debajo. */
    @Column(name = "sla_crit_pct")
    @Builder.Default
    private Integer slaCritPct = 75;

    /** Envíos en riesgo como % de los envíos activos: verde si ≤ este valor. */
    @Column(name = "risk_shipments_warn_pct")
    @Builder.Default
    private Integer riskShipmentsWarnPct = 10;

    /** Envíos en riesgo como % de los activos: ámbar si ≤ este valor; rojo por encima. */
    @Column(name = "risk_shipments_crit_pct")
    @Builder.Default
    private Integer riskShipmentsCritPct = 25;

    /** Nodos críticos como % del total de aeropuertos: verde si ≤ este valor. */
    @Column(name = "critical_nodes_warn_pct")
    @Builder.Default
    private Integer criticalNodesWarnPct = 10;

    /** Nodos críticos como % del total: ámbar si ≤ este valor; rojo por encima. */
    @Column(name = "critical_nodes_crit_pct")
    @Builder.Default
    private Integer criticalNodesCritPct = 25;
}
