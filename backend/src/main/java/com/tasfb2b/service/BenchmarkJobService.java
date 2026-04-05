package com.tasfb2b.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class BenchmarkJobService {

    private final BenchmarkTuningService benchmarkTuningService;

    private final Map<String, BenchmarkJobState> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestJobId = new AtomicReference<>(null);

    public String start() {
        String current = latestJobId.get();
        if (current != null) {
            BenchmarkJobState existing = jobs.get(current);
            if (existing != null && "RUNNING".equals(existing.status())) {
                return current;
            }
        }

        String id = UUID.randomUUID().toString();
        BenchmarkJobState state = new BenchmarkJobState(
                id,
                "RUNNING",
                "Inicializando benchmark...",
                LocalDateTime.now(),
                null,
                null
        );
        jobs.put(id, state);
        latestJobId.set(id);

        Thread worker = new Thread(() -> runJob(id), "benchmark-job-" + id.substring(0, 8));
        worker.setDaemon(true);
        worker.start();

        return id;
    }

    public BenchmarkJobState get(String id) {
        BenchmarkJobState state = jobs.get(id);
        if (state == null) {
            throw new IllegalArgumentException("Job no encontrado: " + id);
        }
        return state;
    }

    public BenchmarkJobState latest() {
        String id = latestJobId.get();
        if (id == null) {
            return null;
        }
        return jobs.get(id);
    }

    private void runJob(String id) {
        try {
            update(id, "RUNNING", "Generando escenarios de demanda...", null);
            var rows = benchmarkTuningService.buildDefaultScenarioRows();

            update(id, "RUNNING", "Creando envios de prueba...", null);
            int created = benchmarkTuningService.generateDemandFromScenarioRows(rows);

            update(id, "RUNNING", "Ejecutando benchmark entre algoritmos...", null);
            var summary = benchmarkTuningService.runBenchmarkAndTune();

            Map<String, Object> result = Map.of(
                    "generatedRows", rows.size(),
                    "createdShipments", created,
                    "winner", summary.winner(),
                    "sampleSize", summary.sampleSize(),
                    "results", summary.results(),
                    "scenarios", summary.scenarios(),
                    "rows", summary.rows(),
                    "bestProfile", summary.bestProfile()
            );
            update(id, "DONE", "Benchmark finalizado", result);
        } catch (Exception ex) {
            update(id, "FAILED", "Benchmark fallo: " + ex.getMessage(), null);
        }
    }

    private void update(String id, String status, String message, Map<String, Object> result) {
        BenchmarkJobState current = jobs.get(id);
        if (current == null) return;
        BenchmarkJobState next = new BenchmarkJobState(
                current.jobId(),
                status,
                message,
                current.startedAt(),
                ("DONE".equals(status) || "FAILED".equals(status)) ? LocalDateTime.now() : null,
                result
        );
        jobs.put(id, next);
    }

    public record BenchmarkJobState(
            String jobId,
            String status,
            String message,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Map<String, Object> result
    ) {}
}
