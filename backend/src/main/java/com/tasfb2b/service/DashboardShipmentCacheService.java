package com.tasfb2b.service;

import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.projection.ShipmentSummaryRow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class DashboardShipmentCacheService {

    private static final long TTL_MS = 45_000L;

    private final ShipmentRepository shipmentRepository;
    private final Map<String, CachedRows> cache = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void warmup() {
        refreshSync(null, 240);
        refreshSync("IN_ROUTE", 240);
        refreshSync(null, 900);
        refreshSync("IN_ROUTE", 900);
    }

    @Scheduled(initialDelay = 6_000L, fixedDelay = 10_000L)
    void keepWarm() {
        refreshAsync(null, 240);
        refreshAsync("IN_ROUTE", 240);
        refreshAsync(null, 900);
        refreshAsync("IN_ROUTE", 900);
    }

    public List<ShipmentSnapshotRow> getRows(String status, int limit) {
        String key = (status == null ? "ALL" : status) + "|" + limit;
        long now = System.currentTimeMillis();
        CachedRows cached = cache.get(key);
        if (cached != null && now - cached.cachedAtMs() <= TTL_MS) {
            return cached.rows();
        }

        if (cached != null) {
            refreshAsync(status, limit);
            return cached.rows();
        }

        refreshSync(status, limit);
        CachedRows loaded = cache.get(key);
        return loaded == null ? List.of() : loaded.rows();
    }

    private void refreshSync(String status, int limit) {
        String key = (status == null ? "ALL" : status) + "|" + limit;
        try {
            List<ShipmentSnapshotRow> rows = shipmentRepository.fetchDashboardSummaryRows(status, limit)
                    .stream()
                    .map(this::toSnapshot)
                    .toList();
            cache.put(key, new CachedRows(System.currentTimeMillis(), rows));
        } catch (Exception ignored) {
            // En error se conserva el cache anterior.
        }
    }

    private void refreshAsync(String status, int limit) {
        String key = (status == null ? "ALL" : status) + "|" + limit;
        if (!refreshing.add(key)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshSync(status, limit);
            } finally {
                refreshing.remove(key);
            }
        });
    }

    private ShipmentSnapshotRow toSnapshot(ShipmentSummaryRow row) {
        return new ShipmentSnapshotRow(
                row.getId(),
                row.getShipmentCode(),
                row.getAirlineName(),
                row.getOriginIcao(),
                row.getOriginLatitude(),
                row.getOriginLongitude(),
                row.getDestinationIcao(),
                row.getDestinationLatitude(),
                row.getDestinationLongitude(),
                row.getStatus(),
                row.getProgressPercentage(),
                row.getDeadline()
        );
    }

    private record CachedRows(long cachedAtMs, List<ShipmentSnapshotRow> rows) {
    }

    public record ShipmentSnapshotRow(
            Long id,
            String shipmentCode,
            String airlineName,
            String originIcao,
            Double originLatitude,
            Double originLongitude,
            String destinationIcao,
            Double destinationLatitude,
            Double destinationLongitude,
            com.tasfb2b.model.ShipmentStatus status,
            Double progressPercentage,
            LocalDateTime deadline
    ) {
    }
}
