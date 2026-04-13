package com.tasfb2b.service;

import com.tasfb2b.dto.SlaBreakdownRowDto;
import com.tasfb2b.dto.SlaReportDto;
import com.tasfb2b.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ReportingService {

    private final ShipmentRepository shipmentRepository;
    private final Map<String, CachedSlaReport> slaCache = new ConcurrentHashMap<>();
    private static final long SLA_CACHE_TTL_MS = 45_000L;

    @Transactional(readOnly = true)
    public SlaReportDto slaReport(LocalDate from, LocalDate to) {
        LocalDate fromDate = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate toDate = to == null ? LocalDate.now() : to;
        String cacheKey = fromDate + "|" + toDate;
        CachedSlaReport cached = slaCache.get(cacheKey);
        long nowMillis = System.currentTimeMillis();
        if (cached != null && nowMillis - cached.cachedAtMs() <= SLA_CACHE_TTL_MS) {
            return cached.payload();
        }

        LocalDateTime fromTs = fromDate.atStartOfDay();
        LocalDateTime toTs = toDate.plusDays(1).atStartOfDay();

        List<SlaBreakdownRowDto> rows = new ArrayList<>();
        rows.addAll(toRows("ROUTE_TYPE", shipmentRepository.slaByRouteType(fromTs, toTs)));
        rows.addAll(toRows("CLIENT", shipmentRepository.slaByClient(fromTs, toTs)));
        rows.addAll(toRows("DESTINATION", shipmentRepository.slaByDestination(fromTs, toTs)));

        SlaReportDto payload = new SlaReportDto(fromDate, toDate, rows);
        slaCache.put(cacheKey, new CachedSlaReport(nowMillis, payload));
        return payload;
    }

    private List<SlaBreakdownRowDto> toRows(String dimension, List<Object[]> aggregates) {
        return aggregates.stream()
                .map(row -> {
                    String group = row[0] == null ? "SIN_DATO" : row[0].toString();
                    long total = row[1] == null ? 0L : ((Number) row[1]).longValue();
                    long onTime = row[2] == null ? 0L : ((Number) row[2]).longValue();
                    double pct = total == 0 ? 0.0 : (onTime * 100.0) / total;
                    return new SlaBreakdownRowDto(dimension, group, total, onTime, pct);
                })
                .sorted(Comparator.comparingDouble(SlaBreakdownRowDto::onTimePct).reversed())
                .toList();
    }

    private record CachedSlaReport(long cachedAtMs, SlaReportDto payload) {}
}
