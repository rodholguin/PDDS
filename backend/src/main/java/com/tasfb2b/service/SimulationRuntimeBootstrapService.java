package com.tasfb2b.service;

import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.SimulationConfigRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationRuntimeBootstrapService {

    private final SimulationConfigRepository simulationConfigRepository;
    private final SimulationRuntimeService simulationRuntimeService;

    @EventListener(ApplicationReadyEvent.class)
    public void rehydrateRuntimeIfNeeded() {
        SimulationConfig config = simulationConfigRepository.findTopByOrderByIdAsc();
        if (config == null) {
            return;
        }

        LocalDateTime anchor = config.getRuntimeSimulatedNow();
        if (anchor == null) {
            anchor = config.getEffectiveScenarioStartAt();
        }
        if (anchor == null) {
            anchor = config.getScenarioStartAt();
        }
        if (anchor == null && config.getProjectedFrom() != null) {
            anchor = config.getProjectedFrom().atStartOfDay();
        }
        if (anchor == null) {
            log.warn("Simulación marcada como corriendo pero sin ancla temporal; no se pudo rehidratar runtime");
            return;
        }

        LocalDateTime lastTickAt = config.getRuntimeLastTickAt();
        if (lastTickAt == null) {
            lastTickAt = LocalDateTime.now();
        }

        simulationRuntimeService.restoreRuntime(anchor, lastTickAt);
        config.setIsRunning(false);
        simulationConfigRepository.save(config);
        log.info("Runtime de simulación rehidratado en arranque con tiempo {}", anchor);
    }
}
