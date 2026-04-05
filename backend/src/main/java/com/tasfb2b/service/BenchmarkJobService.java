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
                    "bestProfile", summary.bestProfile(),
                    "confidence", computeWinnerConfidence(summary)
            );
            update(id, "DONE", "Benchmark finalizado", result);
        } catch (Exception ex) {
            update(id, "FAILED", "Benchmark fallo: " + ex.getMessage(), null);
        }
    }

    private Map<String, Object> computeWinnerConfidence(BenchmarkTuningService.BenchmarkSummary summary) {
        if (summary == null || summary.bestProfile() == null || summary.rows() == null || summary.rows().isEmpty()) {
            return Map.of("winner", "N/A", "ci95Low", 0.0, "ci95High", 0.0, "deltaVsRunnerUp", 0.0);
        }

        String winnerProfile = summary.bestProfile().profile().profileName();
        var grouped = summary.rows().stream().collect(java.util.stream.Collectors.groupingBy(
                BenchmarkTuningService.BenchmarkRow::profileName,
                java.util.stream.Collectors.mapping(BenchmarkTuningService.BenchmarkRow::compositeScore, java.util.stream.Collectors.toList())
        ));

        java.util.List<Double> winnerScores = grouped.getOrDefault(winnerProfile, java.util.List.of());
        double mean = winnerScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double std = stdDev(winnerScores, mean);
        double margin = winnerScores.isEmpty() ? 0.0 : 1.96 * (std / Math.sqrt(winnerScores.size()));

        double winnerMean = mean;
        double runnerUpMean = grouped.entrySet().stream()
                .filter(e -> !e.getKey().equals(winnerProfile))
                .mapToDouble(e -> e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0))
                .max()
                .orElse(0.0);

        return Map.of(
                "winner", summary.winner(),
                "ci95Low", winnerMean - margin,
                "ci95High", winnerMean + margin,
                "deltaVsRunnerUp", winnerMean - runnerUpMean
        );
    }

    private double stdDev(java.util.List<Double> values, double mean) {
        if (values == null || values.size() < 2) {
            return 0.0;
        }
        double variance = values.stream()
                .mapToDouble(v -> {
                    double d = v - mean;
                    return d * d;
                })
                .sum() / (values.size() - 1);
        return Math.sqrt(Math.max(0.0, variance));
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
