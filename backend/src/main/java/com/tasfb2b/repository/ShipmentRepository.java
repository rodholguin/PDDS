package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByShipmentCode(String shipmentCode);

    /** Envíos en un estado dado (sin paginación). */
    List<Shipment> findByStatus(ShipmentStatus status);

    /** Envíos en un estado dado con paginación (para el endpoint GET /api/shipments). */
    Page<Shipment> findByStatus(ShipmentStatus status, Pageable pageable);

    /** Todos los envíos paginados (cuando no se filtra por status). */
    Page<Shipment> findAll(Pageable pageable);

    /**
     * Envíos que han superado su deadline y no están entregados.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.deadline < :now
              AND s.status NOT IN ('DELIVERED')
            ORDER BY s.deadline ASC
            """)
    List<Shipment> findOverdueShipments(@Param("now") LocalDateTime now);

    /** Envíos que pasan por un aeropuerto (como origen o destino). */
    List<Shipment> findByOriginAirportOrDestinationAirport(
            Airport originAirport, Airport destinationAirport);

    /** Envíos activos (en tránsito o pendientes). */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
            ORDER BY s.deadline ASC
            """)
    List<Shipment> findActiveShipments();

    /** Envíos en estado CRITICAL u DELAYED para alertas. */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('CRITICAL', 'DELAYED')
            ORDER BY s.deadline ASC
            """)
    List<Shipment> findCriticalShipments();
}
