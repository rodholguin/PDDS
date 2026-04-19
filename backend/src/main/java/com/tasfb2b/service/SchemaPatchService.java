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
    public void patchSimulationConfigColumns() {
        jdbcTemplate.execute("""
                ALTER TABLE simulation_config
                ADD COLUMN IF NOT EXISTS requested_scenario_start_at TIMESTAMP,
                ADD COLUMN IF NOT EXISTS effective_scenario_start_at TIMESTAMP,
                ADD COLUMN IF NOT EXISTS date_adjusted BOOLEAN NOT NULL DEFAULT FALSE,
                ADD COLUMN IF NOT EXISTS date_adjustment_reason VARCHAR(255),
                ADD COLUMN IF NOT EXISTS runtime_simulated_now TIMESTAMP,
                ADD COLUMN IF NOT EXISTS runtime_last_tick_at TIMESTAMP
                """);
    }
}
