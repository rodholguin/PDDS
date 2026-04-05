package com.tasfb2b.service;

import com.tasfb2b.dto.AlgorithmRaceReportDto;
import com.tasfb2b.dto.BenchmarkMetricsDto;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.service.algorithm.OptimizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AlgorithmRaceService {

    private final RoutePlannerService routePlannerService;
    private final ShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public AlgorithmRaceReportDto buildRaceReport(LocalDate from, LocalDate to, String scenario) {
        List<Shipment> all = shipmentRepository.findAll();
        Map<String, OptimizationResult> map = routePlannerService.runBothAlgorithms(all, from, to);
        String winner = routePlannerService.benchmarkWinner(all, from, to);

        List<BenchmarkMetricsDto> metrics = map.values().stream()
                .map(this::toMetric)
                .sorted(Comparator.comparing(BenchmarkMetricsDto::completedPct).reversed())
                .toList();

        List<String> notes = new ArrayList<>();
        notes.add("Score primario: completedPct, desempate por avgTransitHours y flightUtilizationPct.");
        notes.add("Muestra tomada de envios persistidos en base para la ventana indicada.");
        notes.add("Para benchmark reproducible se recomienda generar lote synthetic con semilla fija.");

        return new AlgorithmRaceReportDto(
                winner,
                scenario == null || scenario.isBlank() ? "DEFAULT" : scenario,
                from == null ? null : from.toString(),
                to == null ? null : to.toString(),
                java.time.Instant.now().toEpochMilli(),
                metrics,
                notes
        );
    }

    private BenchmarkMetricsDto toMetric(OptimizationResult value) {
        int sample = value.getCompletedShipments() == null ? 0 : value.getCompletedShipments();
        double avgTransit = value.getAvgTransitHours() == null ? 0.0 : value.getAvgTransitHours();
        double p95 = avgTransit * 1.35;
        double completedPct = value.getCompletedPct() == null ? 0.0 : value.getCompletedPct();
        double util = value.getFlightUtilizationPct() == null ? 0.0 : value.getFlightUtilizationPct();
        int replans = value.getTotalReplanning() == null ? 0 : value.getTotalReplanning();
        int saturated = value.getSaturatedAirports() == null ? 0 : value.getSaturatedAirports();
        double opCost = value.getOperationalCost() == null ? 0.0 : value.getOperationalCost();

        double costPerLuggage = sample == 0 ? opCost : opCost / Math.max(1, sample * 2);
        int collapseEvents = value.getCollapseReachedAt() == null ? 0 : 1;

        return new BenchmarkMetricsDto(
                value.getAlgorithmName(),
                sample,
                completedPct,
                avgTransit,
                p95,
                costPerLuggage,
                util,
                saturated,
                replans,
                collapseEvents
        );
    }
}
