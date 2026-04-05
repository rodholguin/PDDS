package com.tasfb2b.service;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditType;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.model.TravelStop;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ShipmentOrchestratorService {

    private final AirportRepository airportRepository;
    private final ShipmentRepository shipmentRepository;
    private final SimulationConfigRepository simulationConfigRepository;
    private final RoutePlannerService routePlannerService;
    private final ShipmentAuditService shipmentAuditService;

    private final AtomicInteger sequence = new AtomicInteger(1000);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Shipment createAndPlan(ShipmentCreateDto dto) {
        Airport origin = airportRepository.findByIcaoCode(dto.originIcao().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto origen no encontrado: " + dto.originIcao()));
        Airport destination = airportRepository.findByIcaoCode(dto.destinationIcao().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto destino no encontrado: " + dto.destinationIcao()));

        if (origin.getId().equals(destination.getId())) {
            throw new IllegalArgumentException("El origen y destino no pueden ser iguales");
        }

        int requested = dto.luggageCount();
        int projectedLoad = origin.getCurrentStorageLoad() + requested;
        if (projectedLoad > origin.getMaxStorageCapacity()) {
            throw new IllegalArgumentException("Capacidad maxima del nodo excedida");
        }

        Shipment shipment = Shipment.builder()
                .shipmentCode(generateCode())
                .airlineName(dto.airlineName().trim())
                .originAirport(origin)
                .destinationAirport(destination)
                .luggageCount(requested)
                .registrationDate(dto.registrationDate() == null ? LocalDateTime.now() : dto.registrationDate())
                .status(ShipmentStatus.PENDING)
                .progressPercentage(0.0)
                .build();

        shipmentRepository.save(shipment);

        shipmentAuditService.log(
                shipment,
                ShipmentAuditType.CREATED,
                "Envio creado y en espera de planificacion",
                origin,
                null
        );

        String algorithmName = dto.algorithmName() == null
                ? activeAlgorithmName()
                : dto.algorithmName();
        List<TravelStop> plannedStops = routePlannerService.planShipment(shipment, algorithmName);
        if (plannedStops.isEmpty()) {
            shipment.setStatus(ShipmentStatus.CRITICAL);
            shipmentRepository.save(shipment);
        }
        return shipment;
    }

    @Transactional(readOnly = true)
    public com.tasfb2b.dto.ShipmentFeasibilityDto checkFeasibility(ShipmentCreateDto dto) {
        Airport origin = airportRepository.findByIcaoCode(dto.originIcao().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto origen no encontrado: " + dto.originIcao()));
        Airport destination = airportRepository.findByIcaoCode(dto.destinationIcao().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto destino no encontrado: " + dto.destinationIcao()));

        if (origin.getId().equals(destination.getId())) {
            throw new IllegalArgumentException("El origen y destino no pueden ser iguales");
        }

        int requested = dto.luggageCount();
        int projectedLoad = origin.getCurrentStorageLoad() + requested;
        if (projectedLoad > origin.getMaxStorageCapacity()) {
            throw new IllegalArgumentException("Capacidad maxima del nodo excedida");
        }

        Shipment shadow = Shipment.builder()
                .shipmentCode("SHADOW")
                .airlineName(dto.airlineName().trim())
                .originAirport(origin)
                .destinationAirport(destination)
                .luggageCount(requested)
                .registrationDate(dto.registrationDate() == null ? LocalDateTime.now() : dto.registrationDate())
                .status(ShipmentStatus.PENDING)
                .progressPercentage(0.0)
                .isInterContinental(origin.getContinent() != destination.getContinent())
                .build();
        shadow.setDeadline(shadow.getRegistrationDate().plusDays(Boolean.TRUE.equals(shadow.getIsInterContinental()) ? 2 : 1));

        String algorithm = dto.algorithmName() == null ? activeAlgorithmName() : dto.algorithmName();
        List<TravelStop> stops = routePlannerService.previewRoute(shadow, algorithm);
        boolean feasible = !stops.isEmpty();

        String message = feasible
                ? "Ruta factible dentro de la red actual."
                : "No se encontro ruta que cumpla plazo/capacidad con la red actual.";

        return new com.tasfb2b.dto.ShipmentFeasibilityDto(feasible, message, algorithm, stops.size());
    }

    private String activeAlgorithmName() {
        return simulationConfigRepository.findAll().stream()
                .findFirst()
                .map(config -> config.getPrimaryAlgorithm().name().equals("ANT_COLONY")
                        ? "Ant Colony Optimization"
                        : "Genetic Algorithm")
                .orElse("Genetic Algorithm");
    }

    private String generateCode() {
        int year = LocalDateTime.now().getYear();
        int attempts = 0;
        while (attempts < 10000) {
            int n = sequence.incrementAndGet();
            String code = String.format("ENV-%d-%04d", year, n);
            if (shipmentRepository.findByShipmentCode(code).isEmpty()) {
                return code;
            }
            attempts++;
        }
        throw new IllegalStateException("No se pudo generar un codigo de envio unico");
    }
}
