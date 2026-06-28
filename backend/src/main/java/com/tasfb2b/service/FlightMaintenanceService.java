package com.tasfb2b.service;

import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.repository.FlightRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mantiene ACOTADA la tabla {@code flight} podando clones de vuelo recurrentes SIN uso (sin travel_stop) que
 * caen FUERA de la ventana activa. Sin esto, el planner materializa un clon por día de toda la ventana del
 * escenario (sobre un COLLAPSE de años → millones de clones), lo que termina causando el OOM del planner.
 *
 * <p>La ventana a conservar es la UNIÓN de la operación viva (DAY_TO_DAY, alrededor de "ahora") y la del sim en
 * curso (alrededor de su reloj simulado), para no romper el dual-runtime.</p>
 *
 * <p>GENTIL a propósito: UN solo lote pequeño por ciclo y espaciado, y SE SALTA durante un arranque/detención
 * (bootstrapping/reset). Borrar muchas filas de una tabla con ~11 índices dispara autovacuum y satura el IO del
 * disco de la VM → causaría la lentitud que buscamos evitar y pelearía con el seed del arranque. Los índices
 * PARCIALES (idx_flight_modified / idx_shipment_modified) ya hacen que start/stop y las queries del planner
 * sean O(pocos) AUN con la tabla grande; la poda solo evita el crecimiento sin fin de la corrida.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlightMaintenanceService {

    private static final int PRUNE_BATCH = 8_000;
    private static final long KEEP_BEHIND_HOURS = 24L;
    // Holgura adelante AMPLIA: el planner planifica unas pocas horas/días por delante del reloj; 30 días deja
    // de sobra su lead, pero los clones de meses adelante (de corridas previas) sí caen fuera y se podan.
    private static final long KEEP_AHEAD_DAYS = 30L;

    private final FlightRepository flightRepository;
    private final SimulationConfigRepository simulationConfigRepository;
    private final SimulationRuntimeService runtimeService;
    private final AtomicBoolean pruneInProgress = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 30_000)
    public void pruneUnusedFlightClones() {
        if (!pruneInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            // No podar durante un arranque/detención en curso: el seed materializa los clones del sim que
            // arranca (aún con running=false, fuera de la ventana "viva"); podarlos sería un race y el IO de
            // borrar pelea con el seed. El planner usa este mismo guard.
            if (runtimeService.isBootstrapping() || runtimeService.isResetting()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime keepFrom = now.minusHours(KEEP_BEHIND_HOURS);
            LocalDateTime keepTo = now.plusDays(KEEP_AHEAD_DAYS);

            SimulationConfig sim = simulationConfigRepository.findFirstByScenarioInAndIsRunningTrue(
                    List.of(SimulationScenario.PERIOD_SIMULATION, SimulationScenario.COLLAPSE_TEST)).orElse(null);
            if (sim != null) {
                LocalDateTime simClock = runtimeService.currentSimulationTime(sim).orElse(null);
                if (simClock != null) {
                    LocalDateTime simFrom = simClock.minusHours(KEEP_BEHIND_HOURS);
                    LocalDateTime simTo = simClock.plusDays(KEEP_AHEAD_DAYS);
                    if (simFrom.isBefore(keepFrom)) keepFrom = simFrom;
                    if (simTo.isAfter(keepTo)) keepTo = simTo;
                }
            }

            int deleted = flightRepository.deleteUnusedCloneFlightsOutsideWindow(keepFrom, keepTo, PRUNE_BATCH);
            if (deleted > 0) {
                log.info("Poda de clones de vuelo: {} borrados (conservando [{} .. {}))", deleted, keepFrom, keepTo);
            }
        } catch (Exception ex) {
            log.warn("Poda de clones de vuelo falló: {}", ex.getMessage());
        } finally {
            pruneInProgress.set(false);
        }
    }
}
