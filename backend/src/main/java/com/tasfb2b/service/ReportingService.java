package com.tasfb2b.service;

import com.tasfb2b.dto.SlaBreakdownRowDto;
import com.tasfb2b.dto.SlaReportDto;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportingService {

    private final ShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public SlaReportDto slaReport(LocalDate from, LocalDate to) {
        LocalDate fromDate = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate toDate = to == null ? LocalDate.now() : to;
        LocalDateTime fromTs = fromDate.atStartOfDay();
        LocalDateTime toTs = toDate.plusDays(1).atStartOfDay();

        List<Shipment> periodShipments = shipmentRepository.findAll().stream()
                .filter(s -> s.getRegistrationDate() != null)
                .filter(s -> !s.getRegistrationDate().isBefore(fromTs) && s.getRegistrationDate().isBefore(toTs))
                .filter(s -> s.getStatus() == ShipmentStatus.DELIVERED)
                .toList();

        List<SlaBreakdownRowDto> rows = new ArrayList<>();
        rows.addAll(groupRows("ROUTE_TYPE", periodShipments, s -> Boolean.TRUE.equals(s.getIsInterContinental()) ? "INTER" : "INTRA"));
        rows.addAll(groupRows("CLIENT", periodShipments, Shipment::getAirlineName));
        rows.addAll(groupRows("DESTINATION", periodShipments, s -> s.getDestinationAirport().getIcaoCode()));

        return new SlaReportDto(fromDate, toDate, rows);
    }

    private List<SlaBreakdownRowDto> groupRows(String dimension, List<Shipment> shipments, Function<Shipment, String> keyMapper) {
        Map<String, List<Shipment>> grouped = shipments.stream()
                .collect(Collectors.groupingBy(keyMapper));

        return grouped.entrySet().stream()
                .map(entry -> {
                    long total = entry.getValue().size();
                    long onTime = entry.getValue().stream().filter(Shipment::isDeliveredOnTime).count();
                    double pct = total == 0 ? 0.0 : (onTime * 100.0) / total;
                    return new SlaBreakdownRowDto(dimension, entry.getKey(), total, onTime, pct);
                })
                .sorted((a, b) -> Double.compare(b.onTimePct(), a.onTimePct()))
                .toList();
    }
}
