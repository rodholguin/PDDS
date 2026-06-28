package com.tasfb2b.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SchemaPatchService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void patchSchemaColumns() {
        jdbcTemplate.execute("""
                ALTER TABLE simulation_config
                ADD COLUMN IF NOT EXISTS requested_scenario_start_at TIMESTAMP,
                ADD COLUMN IF NOT EXISTS effective_scenario_start_at TIMESTAMP,
                ADD COLUMN IF NOT EXISTS date_adjusted BOOLEAN NOT NULL DEFAULT FALSE,
                ADD COLUMN IF NOT EXISTS date_adjustment_reason VARCHAR(255),
                ADD COLUMN IF NOT EXISTS runtime_simulated_now TIMESTAMP,
                ADD COLUMN IF NOT EXISTS runtime_last_tick_at TIMESTAMP
                """);

        jdbcTemplate.execute("ALTER TABLE flight ADD COLUMN IF NOT EXISTS reserved_load INTEGER");
        jdbcTemplate.execute("UPDATE flight SET reserved_load = 0 WHERE reserved_load IS NULL");
        jdbcTemplate.execute("ALTER TABLE flight ALTER COLUMN reserved_load SET DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE flight ALTER COLUMN reserved_load SET NOT NULL");

        // DAY_TO_DAY: 'source' separa envíos en vivo (LIVE) de los históricos del dataset (HISTORICAL).
        // DEFAULT HISTORICAL → los ~9.5M existentes quedan como históricos (ADD COLUMN con DEFAULT es
        // metadata-only en Postgres, no reescribe la tabla). DAY_TO_DAY opera SOLO los LIVE.
        jdbcTemplate.execute("ALTER TABLE shipment ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'HISTORICAL'");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_shipment_source_registration_date
                ON shipment (source, registration_date)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_flight_template_departure
                ON flight (scheduled_departure)
                WHERE flight_code !~ 'R[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                """);

        // PERF: índice parcial sobre vuelos MODIFICADOS (carga/estado distintos del default). El reset de
        // start/stop solo toca estos en vez de escanear los millones de clones SCHEDULED/load=0 → reset O(pocos).
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_flight_modified
                ON flight (scheduled_departure)
                WHERE status <> 'SCHEDULED' OR current_load <> 0 OR reserved_load <> 0
                """);
        // PERF: índice parcial sobre envíos MODIFICADOS (no en estado inicial). resetToInitialStateBySource
        // solo toca estos en vez de escanear los ~9.5M → reset O(pocos).
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_shipment_modified
                ON shipment (source)
                WHERE status <> 'PENDING' OR progress_percentage <> 0.0 OR delivered_at IS NOT NULL
                """);
        // PERF: clones de vuelo recurrentes (R+fecha). Acelera la poda rodante (borra clones viejos sin uso
        // detrás del reloj) y cualquier filtro por "es clon".
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_flight_clone_arrival
                ON flight (scheduled_arrival)
                WHERE flight_code ~ 'R[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                """);
        // PERF: el NOT EXISTS de la poda (y los joins por vuelo) necesitan índice en la FK travel_stop.flight_id.
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_travel_stop_flight ON travel_stop (flight_id)");
    }
}
