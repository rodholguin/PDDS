package com.tasfb2b.service;

import com.tasfb2b.dto.ShipmentAuditLogDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditLog;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.repository.ShipmentAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShipmentAuditService {

    private final ShipmentAuditLogRepository auditLogRepository;

    @Transactional
    public void log(Shipment shipment,
                    ShipmentAuditType type,
                    String message,
                    Airport airport,
                    String flightCode) {
        auditLogRepository.save(ShipmentAuditLog.builder()
                .shipment(shipment)
                .eventType(type)
                .message(message)
                .eventAt(LocalDateTime.now())
                .airportIcao(airport == null ? null : airport.getIcaoCode())
                .airportLatitude(airport == null ? null : airport.getLatitude())
                .airportLongitude(airport == null ? null : airport.getLongitude())
                .flightCode(flightCode)
                .build());
    }

    @Transactional(readOnly = true)
    public List<ShipmentAuditLogDto> getAudit(Shipment shipment) {
        return auditLogRepository.findByShipmentOrderByEventAtAsc(shipment).stream()
                .map(log -> new ShipmentAuditLogDto(
                        log.getId(),
                        log.getEventType(),
                        log.getMessage(),
                        log.getEventAt(),
                        log.getAirportIcao(),
                        log.getAirportLatitude(),
                        log.getAirportLongitude(),
                        log.getFlightCode()
                ))
                .toList();
    }
}
