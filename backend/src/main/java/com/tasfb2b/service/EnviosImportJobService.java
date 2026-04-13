package com.tasfb2b.service;

import com.tasfb2b.dto.EnviosDatasetImportRequestDto;
import com.tasfb2b.dto.EnviosDatasetImportResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class EnviosImportJobService {

    private final DataImportService dataImportService;

    private final ConcurrentMap<String, EnviosImportJobState> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestJobId = new AtomicReference<>(null);

    private static final int JOB_HISTORY_LIMIT = 25;

    public String startFullImport() {
        String current = latestJobId.get();
        if (current != null) {
            EnviosImportJobState existing = jobs.get(current);
            if (existing != null && "RUNNING".equals(existing.status())) {
                return current;
            }
        }

        String id = UUID.randomUUID().toString();
        jobs.put(id, new EnviosImportJobState(
                id,
                "RUNNING",
                "Inicializando importacion completa de envios...",
                LocalDateTime.now(),
                null,
                null
        ));
        latestJobId.set(id);

        Thread worker = new Thread(() -> runFullImport(id), "envios-import-job-" + id.substring(0, 8));
        worker.setDaemon(true);
        worker.start();
        return id;
    }

    public EnviosImportJobState get(String jobId) {
        EnviosImportJobState state = jobs.get(jobId);
        if (state == null) {
            throw new IllegalArgumentException("Job no encontrado: " + jobId);
        }
        return state;
    }

    public EnviosImportJobState latest() {
        String id = latestJobId.get();
        if (id == null) return null;
        return jobs.get(id);
    }

    private void runFullImport(String id) {
        try {
            update(id, "RUNNING", "Importando todos los envios oficiales a BD...", null);
            EnviosDatasetImportResultDto result = dataImportService.importShipmentsFromEnviosDataset(
                    new EnviosDatasetImportRequestDto(7, 60, 50000, null, true, null)
            );

            int accounting = result.importedRows() + result.failedRows();
            boolean coherent = accounting == result.requestedRows();
            String message = coherent
                    ? "Importacion completa finalizada"
                    : "Importacion finalizada con alerta de consistencia";

            update(id, "DONE", message, result);
        } catch (Exception ex) {
            update(id, "FAILED", "Importacion full fallo: " + ex.getMessage(), null);
        }
    }

    private void update(String id, String status, String message, EnviosDatasetImportResultDto result) {
        EnviosImportJobState current = jobs.get(id);
        if (current == null) return;

        jobs.put(id, new EnviosImportJobState(
                id,
                status,
                message,
                current.startedAt(),
                ("DONE".equals(status) || "FAILED".equals(status)) ? LocalDateTime.now() : null,
                result
        ));

        if ("DONE".equals(status) || "FAILED".equals(status)) {
            trimJobHistory();
        }
    }

    private void trimJobHistory() {
        if (jobs.size() <= JOB_HISTORY_LIMIT) {
            return;
        }

        String latest = latestJobId.get();
        jobs.values().stream()
                .filter(job -> !job.jobId().equals(latest))
                .filter(job -> "DONE".equals(job.status()) || "FAILED".equals(job.status()))
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.finishedAt() == null ? a.startedAt() : a.finishedAt();
                    LocalDateTime bTime = b.finishedAt() == null ? b.startedAt() : b.finishedAt();
                    return aTime.compareTo(bTime);
                })
                .limit(Math.max(0, jobs.size() - JOB_HISTORY_LIMIT))
                .forEach(job -> jobs.remove(job.jobId()));
    }

    public record EnviosImportJobState(
            String jobId,
            String status,
            String message,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            EnviosDatasetImportResultDto result
    ) {}
}
