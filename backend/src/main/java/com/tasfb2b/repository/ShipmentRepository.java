package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Optional<Shipment> findByShipmentCode(String shipmentCode);

    @Override
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Optional<Shipment> findById(Long id);

    /** Envíos en un estado dado (sin paginación). */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findByStatus(ShipmentStatus status);

    /** Envíos en un estado dado con paginación (para el endpoint GET /api/shipments). */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> findByStatus(ShipmentStatus status, Pageable pageable);

    /** Todos los envíos paginados (cuando no se filtra por status). */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
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
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findOverdueShipments(@Param("now") LocalDateTime now);

    /** Envíos que pasan por un aeropuerto (como origen o destino). */
    List<Shipment> findByOriginAirportOrDestinationAirport(
            Airport originAirport, Airport destinationAirport);

    /** Envíos de una aerolínea/remitente exacta. */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findByAirlineNameIgnoreCase(String airlineName);

    /** Conteo de envíos registrados en un rango de fechas. */
    long countByRegistrationDateBetween(LocalDateTime from, LocalDateTime to);

    /** Conteo de envíos entregados en un rango de fechas. */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'DELIVERED'
              AND s.deliveredAt >= :from
              AND s.deliveredAt < :to
            """)
    long countDeliveredBetween(@Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    /** Conteo total de envíos entregados (cerrados). */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'DELIVERED'
            """)
    long countDeliveredTotal();

    /** Conteo total de envíos entregados dentro del plazo comprometido. */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'DELIVERED'
              AND s.deliveredAt IS NOT NULL
              AND s.deadline IS NOT NULL
              AND s.deliveredAt <= s.deadline
            """)
    long countDeliveredOnTimeTotal();

    /** Conteo de envíos entregados dentro de plazo en un rango temporal. */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'DELIVERED'
              AND s.deliveredAt IS NOT NULL
              AND s.deadline IS NOT NULL
              AND s.deliveredAt <= s.deadline
              AND s.deliveredAt >= :from
              AND s.deliveredAt < :to
            """)
    long countDeliveredOnTimeBetween(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /** Conteo de envíos activos en ruta para monitoreo. */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'IN_ROUTE'
            """)
    long countInRoute();

    /** Conteo de envíos activos por tipo de ruta (intra/inter). */
    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.isInterContinental = :inter
            """)
    long countActiveByRouteType(@Param("inter") boolean inter);

    /** Envíos con riesgo de incumplimiento (ETA > deadline o vencido sin entregar). */
    @Query("""
            SELECT DISTINCT s FROM Shipment s
            LEFT JOIN TravelStop ts ON ts.shipment = s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND (
                    (ts.scheduledArrival IS NOT NULL AND ts.scheduledArrival > s.deadline)
                 OR (s.deadline < :now AND s.status <> 'DELIVERED')
              )
            ORDER BY s.deadline ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findAtRiskShipments(@Param("now") LocalDateTime now);

    /**
     * Búsqueda compuesta para filtros del mapa y gestión de envíos.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:status IS NULL OR s.status = :status)
            ORDER BY s.registrationDate DESC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> searchShipments(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("status") ShipmentStatus status
    );

    @Query("""
            SELECT s FROM Shipment s
            WHERE (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:status IS NULL OR s.status = :status)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchShipmentsPage(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("status") ShipmentStatus status,
            @Param("code") String code,
            Pageable pageable
    );

    /**
     * Envíos sin movimiento en un umbral de horas: todos sus stops siguen PENDING
     * y el registro es anterior al umbral.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.registrationDate < :threshold
              AND NOT EXISTS (
                    SELECT ts FROM TravelStop ts
                    WHERE ts.shipment = s
                      AND ts.stopStatus IN ('IN_TRANSIT', 'COMPLETED')
              )
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findShipmentsWithoutMovement(@Param("threshold") LocalDateTime threshold);

    /** Envíos activos (en tránsito o pendientes). */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
            ORDER BY s.deadline ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findActiveShipments();

    /** Envíos en estado CRITICAL u DELAYED para alertas. */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('CRITICAL', 'DELAYED')
            ORDER BY s.deadline ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findCriticalShipments();
}
