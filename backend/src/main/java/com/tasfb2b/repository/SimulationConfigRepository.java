package com.tasfb2b.repository;

import com.tasfb2b.model.SimulationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, Long> {
    // Los servicios recuperan siempre el singleton con findById(1L)
    // o con findFirst() si el id puede variar.
}
