package com.tasfb2b.service;

import com.tasfb2b.dto.OperationalAlertDto;
import com.tasfb2b.model.OperationalAlert;
import com.tasfb2b.model.OperationalAlertStatus;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.repository.OperationalAlertRepository;
import com.tasfb2b.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationalAlertService {

    private final OperationalAlertRepository alertRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentAuditService shipmentAuditService;

    @Transactional
    public OperationalAlertDto createFromShipment(Long shipmentId, String type, String note) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Envío no encontrado: " + shipmentId));

        OperationalAlert alert = OperationalAlert.builder()
                .shipment(shipment)
                .shipmentCode(shipment.getShipmentCode())
                .type(type)
                .status(OperationalAlertStatus.PENDING)
                .note(note)
                .build();

        return toDto(alertRepository.save(alert));
    }

    @Transactional(readOnly = true)
    public List<OperationalAlertDto> activeAlerts() {
        return alertRepository.findByStatusInOrderByIdDesc(List.of(OperationalAlertStatus.PENDING, OperationalAlertStatus.IN_REVIEW))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public OperationalAlertDto resolve(Long alertId, String user, String note) {
        OperationalAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alerta no encontrada: " + alertId));

        if (note == null || note.trim().isEmpty()) {
            throw new IllegalArgumentException("La nota de resolucion es obligatoria");
        }

        alert.setStatus(OperationalAlertStatus.RESOLVED);
        alert.setResolvedBy(user == null ? "N/A" : user.trim());
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolutionNote(note.trim());

        OperationalAlert saved = alertRepository.save(alert);
        if (saved.getShipment() != null) {
            shipmentAuditService.log(
                    saved.getShipment(),
                    ShipmentAuditType.ALERT_RESOLVED,
                    "Alerta resuelta por " + saved.getResolvedBy() + ": " + saved.getResolutionNote(),
                    saved.getShipment().getOriginAirport(),
                    null
            );
        }
        return toDto(saved);
    }

    private OperationalAlertDto toDto(OperationalAlert alert) {
        return new OperationalAlertDto(
                alert.getId(),
                alert.getShipment() == null ? null : alert.getShipment().getId(),
                alert.getShipmentCode(),
                alert.getType(),
                alert.getStatus().name(),
                alert.getNote(),
                alert.getResolvedBy(),
                alert.getResolvedAt(),
                alert.getResolutionNote()
        );
    }
}
