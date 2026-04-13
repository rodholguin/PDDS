package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.repository.projection.ShipmentSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'CRITICAL', 'DELAYED')
            ORDER BY
              CASE
                WHEN s.status = 'IN_ROUTE' THEN 0
                WHEN s.status = 'PENDING' THEN 1
                WHEN s.status = 'CRITICAL' THEN 2
                WHEN s.status = 'DELAYED' THEN 2
                ELSE 3
              END,
              s.registrationDate DESC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> findActiveForDashboard(Pageable pageable);

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

    long countByStatus(ShipmentStatus status);

    long countByStatusIn(List<ShipmentStatus> statuses);

    @Query(value = """
            SELECT s.*
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date <= :horizon
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            ORDER BY s.registration_date ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Shipment> findPendingWithoutRouteForPlanning(@Param("horizon") LocalDateTime horizon,
                                                      @Param("batchSize") int batchSize);

    @Query("SELECT MIN(s.registrationDate) FROM Shipment s")
    LocalDateTime findMinRegistrationDate();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Shipment s
            SET s.status = 'DELAYED'
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.deadline IS NOT NULL
              AND s.deadline < :now
              AND EXISTS (
                    SELECT ts FROM TravelStop ts
                    WHERE ts.shipment = s
              )
            """)
    int markActiveAsDelayedBefore(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Shipment s
            SET s.status = 'PENDING',
                s.progressPercentage = 0.0,
                s.deliveredAt = null
            WHERE s.status <> 'PENDING'
               OR s.progressPercentage <> 0.0
               OR s.deliveredAt IS NOT NULL
            """)
    int resetAllToInitialState();

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.deadline < :now
              AND s.status <> 'DELIVERED'
            """)
    long countOverdueShipments(@Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(DISTINCT s) FROM Shipment s
            LEFT JOIN TravelStop ts ON ts.shipment = s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND (
                    (ts.scheduledArrival IS NOT NULL AND ts.scheduledArrival > s.deadline)
                 OR (s.deadline < :now AND s.status <> 'DELIVERED')
              )
            """)
    long countAtRiskShipments(@Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.registrationDate < :threshold
              AND NOT EXISTS (
                    SELECT ts FROM TravelStop ts
                    WHERE ts.shipment = s
                      AND ts.stopStatus IN ('IN_TRANSIT', 'COMPLETED')
              )
            """)
    long countShipmentsWithoutMovement(@Param("threshold") LocalDateTime threshold);

    @Query(value = """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (s.delivered_at - s.registration_date)) / 3600.0), 0)
            FROM shipment s
            WHERE s.status = 'DELIVERED'
              AND s.delivered_at >= :from
              AND s.delivered_at < :to
            """, nativeQuery = true)
    double avgDeliveryHoursBetween(@Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (s.deadline - s.registration_date)) / 3600.0), 0)
            FROM shipment s
            WHERE s.status = 'DELIVERED'
              AND s.delivered_at >= :from
              AND s.delivered_at < :to
            """, nativeQuery = true)
    double avgCommittedHoursBetween(@Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT
              CASE WHEN s.is_inter_continental THEN 'INTER' ELSE 'INTRA' END AS grp,
              COUNT(*) AS total,
              SUM(CASE WHEN s.delivered_at IS NOT NULL AND s.deadline IS NOT NULL AND s.delivered_at <= s.deadline THEN 1 ELSE 0 END) AS on_time
            FROM shipment s
            WHERE s.status = 'DELIVERED'
              AND s.registration_date >= :from
              AND s.registration_date < :to
            GROUP BY CASE WHEN s.is_inter_continental THEN 'INTER' ELSE 'INTRA' END
            """, nativeQuery = true)
    List<Object[]> slaByRouteType(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT
              COALESCE(NULLIF(TRIM(s.airline_name), ''), 'SIN_CLIENTE') AS grp,
              COUNT(*) AS total,
              SUM(CASE WHEN s.delivered_at IS NOT NULL AND s.deadline IS NOT NULL AND s.delivered_at <= s.deadline THEN 1 ELSE 0 END) AS on_time
            FROM shipment s
            WHERE s.status = 'DELIVERED'
              AND s.registration_date >= :from
              AND s.registration_date < :to
            GROUP BY COALESCE(NULLIF(TRIM(s.airline_name), ''), 'SIN_CLIENTE')
            """, nativeQuery = true)
    List<Object[]> slaByClient(@Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT
              COALESCE(a.icao_code, 'SIN_DESTINO') AS grp,
              COUNT(*) AS total,
              SUM(CASE WHEN s.delivered_at IS NOT NULL AND s.deadline IS NOT NULL AND s.delivered_at <= s.deadline THEN 1 ELSE 0 END) AS on_time
            FROM shipment s
            LEFT JOIN airport a ON a.id = s.destination_airport_id
            WHERE s.status = 'DELIVERED'
              AND s.registration_date >= :from
              AND s.registration_date < :to
            GROUP BY COALESCE(a.icao_code, 'SIN_DESTINO')
            """, nativeQuery = true)
    List<Object[]> slaByDestination(@Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT
                s.id AS id,
                s.shipment_code AS shipmentCode,
                s.airline_name AS airlineName,
                ao.icao_code AS originIcao,
                ao.latitude AS originLatitude,
                ao.longitude AS originLongitude,
                ad.icao_code AS destinationIcao,
                ad.latitude AS destinationLatitude,
                ad.longitude AS destinationLongitude,
                s.status AS status,
                s.progress_percentage AS progressPercentage,
                s.deadline AS deadline
            FROM shipment s
            JOIN airport ao ON ao.id = s.origin_airport_id
            JOIN airport ad ON ad.id = s.destination_airport_id
            WHERE (:status IS NULL AND s.status IN ('PENDING', 'IN_ROUTE', 'CRITICAL', 'DELAYED') OR s.status = CAST(:status AS varchar))
            ORDER BY
                CASE
                    WHEN s.status = 'IN_ROUTE' THEN 0
                    WHEN s.status = 'PENDING' THEN 1
                    WHEN s.status = 'CRITICAL' THEN 2
                    WHEN s.status = 'DELAYED' THEN 2
                    ELSE 3
                END,
                s.registration_date DESC
            LIMIT :limitRows
            """, nativeQuery = true)
    List<ShipmentSummaryRow> fetchDashboardSummaryRows(@Param("status") String status,
                                                       @Param("limitRows") int limitRows);
}
