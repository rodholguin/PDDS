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
        SimulationConfig config = simulationConfigRepository.findFirstByScenario(com.tasfb2b.model.SimulationScenario.DAY_TO_DAY).orElse(null);
        if (config == null) {
            return;
        }

        // DAY_TO_DAY es la operación EN VIVO (NO una simulación): no requiere arranque manual. Debe
        // auto-arrancar aunque no exista ancla previa, usando la hora real cotidiana.
        if (config.getScenario() == com.tasfb2b.model.SimulationScenario.DAY_TO_DAY) {
            LocalDateTime now = LocalDateTime.now();
            config.setRuntimeSimulatedNow(now);
            config.setRuntimeLastTickAt(now);
            config.setIsRunning(true);
            simulationConfigRepository.save(config);
            log.info("DAY_TO_DAY (operación en vivo) auto-arrancada en hora real {}", now);
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
