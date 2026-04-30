package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentStatus;
import com.tasfb2b.repository.projection.FutureRouteBaselineRow;
import com.tasfb2b.repository.projection.ShipmentSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Date;
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
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countActiveByRouteTypeBetween(@Param("inter") boolean inter,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status <> 'DELIVERED'
              AND (s.originAirport = :airport OR s.destinationAirport = :airport)
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countStoredByAirportWithinDay(@Param("airport") Airport airport,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'CRITICAL', 'DELAYED')
              AND s.destinationAirport = :airport
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countInboundByAirportWithinDay(@Param("airport") Airport airport,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'CRITICAL', 'DELAYED')
              AND s.originAirport = :airport
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countOutboundByAirportWithinDay(@Param("airport") Airport airport,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

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
            WHERE (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:status IS NULL OR s.status = :status)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
              AND s.registrationDate >= :dateFrom
              AND s.registrationDate < :dateTo
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchShipmentsPageOnDate(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("status") ShipmentStatus status,
            @Param("code") String code,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'DELIVERED'
              AND (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
              AND s.deliveredAt >= :dateFrom
              AND s.deliveredAt < :dateTo
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchDeliveredShipmentsPageOnDate(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("code") String code,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM Shipment s
            WHERE (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:status IS NULL OR s.status = :status)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
              AND s.registrationDate >= :dateFrom
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchShipmentsPageFromDate(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("status") ShipmentStatus status,
            @Param("code") String code,
            @Param("dateFrom") LocalDateTime dateFrom,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'DELIVERED'
              AND (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
              AND s.deliveredAt >= :dateFrom
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchDeliveredShipmentsPageFromDate(
            @Param("airline") String airline,
            @Param("originIcao") String originIcao,
            @Param("destinationIcao") String destinationIcao,
            @Param("code") String code,
            @Param("dateFrom") LocalDateTime dateFrom,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'PENDING'
            ORDER BY
              s.registrationDate DESC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> findActiveForDashboard(Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> findVisibleForOperationalDay(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
              AND (:airline IS NULL OR UPPER(s.airlineName) = :airline)
              AND (:originIcao IS NULL OR UPPER(s.originAirport.icaoCode) = :originIcao)
              AND (:destinationIcao IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destinationIcao)
              AND (:status IS NULL OR s.status = :status)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> searchVisibleForOperationalDay(@Param("airline") String airline,
                                                  @Param("originIcao") String originIcao,
                                                  @Param("destinationIcao") String destinationIcao,
                                                  @Param("status") ShipmentStatus status,
                                                  @Param("code") String code,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to,
                                                  Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findVisibleForMapWithinDay(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countVisibleForMapWithinDay(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status IN ('IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND s.registrationDate >= :from
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findVisibleForMapSince(@Param("from") LocalDateTime from);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'IN_ROUTE'
              AND s.registrationDate >= :from
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findInRouteSince(@Param("from") LocalDateTime from);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'IN_ROUTE'
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findInRouteWithinDay(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND s.registrationDate >= :from
            """)
    long countVisibleForMapSince(@Param("from") LocalDateTime from);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'IN_ROUTE'
              AND s.registrationDate >= :from
            """)
    long countInRouteSince(@Param("from") LocalDateTime from);

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

    @Query("""
            SELECT COALESCE(SUM(s.luggageCount), 0) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
            """)
    long sumActiveLuggage();

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

    long countByStatusAndRegistrationDateBetween(ShipmentStatus status, LocalDateTime from, LocalDateTime to);

    boolean existsByStatusNot(ShipmentStatus status);

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

    @Query(value = """
            SELECT s.*
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date >= :windowStart
              AND s.registration_date <= :horizon
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            ORDER BY s.registration_date ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Shipment> findPendingWithoutRouteForPlanningInWindow(@Param("windowStart") LocalDateTime windowStart,
                                                              @Param("horizon") LocalDateTime horizon,
                                                              @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT COUNT(*)
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date >= :windowStart
              AND s.registration_date < :windowEnd
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            """, nativeQuery = true)
    long countPendingWithoutRouteForPlanningInWindow(@Param("windowStart") LocalDateTime windowStart,
                                                     @Param("windowEnd") LocalDateTime windowEnd);

    @Query(value = """
            SELECT s.*
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date >= :periodStart
              AND s.registration_date < :periodEnd
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            ORDER BY s.registration_date ASC, s.id ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Shipment> findPendingWithoutRouteForPlanningInPeriod(@Param("periodStart") LocalDateTime periodStart,
                                                              @Param("periodEnd") LocalDateTime periodEnd,
                                                              @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT COUNT(*)
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date >= :periodStart
              AND s.registration_date < :periodEnd
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            """, nativeQuery = true)
    long countPendingWithoutRouteForPlanningInPeriod(@Param("periodStart") LocalDateTime periodStart,
                                                     @Param("periodEnd") LocalDateTime periodEnd);

    @Query(value = """
            SELECT MIN(s.registration_date)
            FROM shipment s
            WHERE s.status = 'PENDING'
              AND s.registration_date >= :periodStart
              AND s.registration_date < :periodEnd
              AND NOT EXISTS (
                    SELECT 1 FROM travel_stop ts
                    WHERE ts.shipment_id = s.id
              )
            """, nativeQuery = true)
    LocalDateTime findEarliestUnplannedRegistrationInPeriod(@Param("periodStart") LocalDateTime periodStart,
                                                            @Param("periodEnd") LocalDateTime periodEnd);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status = 'IN_ROUTE'
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countInRouteBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.deadline < :now
              AND s.status <> 'DELIVERED'
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countOverdueShipmentsBetween(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(DISTINCT s) FROM Shipment s
            LEFT JOIN TravelStop ts ON ts.shipment = s
            WHERE s.status IN ('PENDING', 'IN_ROUTE', 'DELAYED', 'CRITICAL')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
              AND (
                    (ts.scheduledArrival IS NOT NULL AND ts.scheduledArrival > s.deadline)
                 OR (s.deadline < :now AND s.status <> 'DELIVERED')
              )
            """)
    long countAtRiskShipmentsBetween(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(s) FROM Shipment s
            WHERE s.status IN ('PENDING', 'IN_ROUTE')
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
              AND s.registrationDate < :threshold
              AND NOT EXISTS (
                    SELECT ts FROM TravelStop ts
                    WHERE ts.shipment = s
                      AND ts.stopStatus IN ('IN_TRANSIT', 'COMPLETED')
              )
            """)
    long countShipmentsWithoutMovementBetween(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("threshold") LocalDateTime threshold);

    @Query("SELECT MIN(s.registrationDate) FROM Shipment s")
    LocalDateTime findMinRegistrationDate();

    @Query("SELECT MAX(s.registrationDate) FROM Shipment s")
    LocalDateTime findMaxRegistrationDate();

    @Query(value = """
            SELECT COUNT(DISTINCT DATE(s.registration_date))
            FROM shipment s
            WHERE s.registration_date >= :fromTs
              AND s.registration_date < :toTs
            """, nativeQuery = true)
    long countDistinctRegistrationDaysBetween(@Param("fromTs") LocalDateTime fromTs,
                                              @Param("toTs") LocalDateTime toTs);

    @Query(value = """
            SELECT DISTINCT DATE(s.registration_date)
            FROM shipment s
            WHERE s.registration_date >= :fromTs
              AND s.registration_date < :toTs
            ORDER BY DATE(s.registration_date)
            """, nativeQuery = true)
    List<Date> findDistinctRegistrationDatesBetween(@Param("fromTs") LocalDateTime fromTs,
                                                    @Param("toTs") LocalDateTime toTs);

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

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'DELAYED'
              AND s.deadline IS NOT NULL
              AND s.deadline < :now
            ORDER BY s.deadline ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findDelayedOverdueShipments(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = 'DELAYED'
              AND s.deadline IS NOT NULL
              AND s.deadline < :now
              AND NOT EXISTS (
                    SELECT a.id FROM OperationalAlert a
                    WHERE a.shipment = s
                      AND a.type = :type
                      AND a.status IN :statuses
              )
            ORDER BY s.deadline ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Shipment> findDelayedOverdueShipmentsWithoutActiveAlert(@Param("now") LocalDateTime now,
                                                                 @Param("type") String type,
                                                                 @Param("statuses") List<com.tasfb2b.model.OperationalAlertStatus> statuses,
                                                                 Pageable pageable);

    @Query(value = """
            SELECT
              UPPER(o.icao_code) AS originIcao,
              UPPER(d.icao_code) AS destinationIcao,
              EXTRACT(ISODOW FROM s.registration_date)::int AS isoDow,
              COUNT(*) AS shipmentCount,
              COALESCE(AVG(s.luggage_count), 0) AS avgLuggage
            FROM shipment s
            JOIN airport o ON o.id = s.origin_airport_id
            JOIN airport d ON d.id = s.destination_airport_id
            WHERE s.registration_date >= :fromTs
              AND s.registration_date < :toTs
            GROUP BY UPPER(o.icao_code), UPPER(d.icao_code), EXTRACT(ISODOW FROM s.registration_date)
            """, nativeQuery = true)
    List<FutureRouteBaselineRow> aggregateFutureBaseline(@Param("fromTs") LocalDateTime fromTs,
                                                         @Param("toTs") LocalDateTime toTs);

    @Query("""
            SELECT s FROM Shipment s
            WHERE ((:status IS NULL AND s.status IN ('PENDING', 'IN_ROUTE', 'CRITICAL', 'DELAYED')) OR s.status = :status)
              AND (:origin IS NULL OR UPPER(s.originAirport.icaoCode) = :origin)
              AND (:destination IS NULL OR UPPER(s.destinationAirport.icaoCode) = :destination)
              AND (:code IS NULL OR UPPER(s.shipmentCode) LIKE :code)
              AND s.registrationDate >= :dateFrom
              AND s.registrationDate < :dateTo
            ORDER BY s.registrationDate ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Shipment> findUpcomingShipmentCandidates(@Param("status") ShipmentStatus status,
                                                  @Param("origin") String origin,
                                                  @Param("destination") String destination,
                                                  @Param("code") String code,
                                                  @Param("dateFrom") LocalDateTime dateFrom,
                                                  @Param("dateTo") LocalDateTime dateTo,
                                                  Pageable pageable);

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

    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(s.shipment_code FROM '[0-9]+$') AS BIGINT)), 0)
            FROM shipment s
            WHERE s.shipment_code LIKE :prefix
            """, nativeQuery = true)
    long findMaxProjectedSequenceByPrefix(@Param("prefix") String prefix);

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

    List<Shipment> findByRegistrationDateBetweenOrderByRegistrationDateAsc(LocalDateTime from,
                                                                            LocalDateTime to);

    long deleteByRegistrationDateAfter(LocalDateTime cutoff);
}
