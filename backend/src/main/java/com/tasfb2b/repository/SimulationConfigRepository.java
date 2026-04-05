package com.tasfb2b.repository;

import com.tasfb2b.model.SimulationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, Long> {
    SimulationConfig findTopByOrderByIdAsc();
}
