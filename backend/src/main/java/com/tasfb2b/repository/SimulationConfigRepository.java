package com.tasfb2b.repository;

import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, Long> {
    SimulationConfig findTopByOrderByIdAsc();

    /** Todos los runtimes en curso (LIVE id=1 y/o SIM id=2) — el motor procesa cada uno por separado. */
    List<SimulationConfig> findByIsRunningTrue();

    /** Primer config de un escenario dado (p. ej. la fila SIM PERIOD/COLLAPSE, separada de la LIVE). */
    Optional<SimulationConfig> findFirstByScenario(SimulationScenario scenario);

    /** Primer config cuyo escenario está en la lista (p. ej. cualquier simulación PERIOD/COLLAPSE). */
    Optional<SimulationConfig> findFirstByScenarioIn(List<SimulationScenario> scenarios);

    Optional<SimulationConfig> findFirstByScenarioInAndIsRunningTrue(List<SimulationScenario> scenarios);

    default SimulationConfig findLiveConfigOrFirst() {
        return findFirstByScenario(SimulationScenario.DAY_TO_DAY).orElseGet(this::findTopByOrderByIdAsc);
    }
}
