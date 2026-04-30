package com.tasfb2b.repository;

import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TravelStopRepository extends JpaRepository<TravelStop, Long> {

    /** Paradas de un envío, ordenadas por posición. */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipmentOrderByStopOrderAsc(Shipment shipment);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipmentInOrderByShipmentIdAscStopOrderAsc(Collection<Shipment> shipments);

    /** Paradas de un envío (sin orden, para conteos rápidos). */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipment(Shipment shipment);

    /** Paradas pendientes de un envío. */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipmentAndStopStatus(Shipment shipment, StopStatus stopStatus);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByStopStatusIn(List<StopStatus> statuses);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByFlightAndStopStatus(Flight flight, StopStatus stopStatus);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByFlightInAndStopStatus(List<Flight> flights, StopStatus stopStatus);

    @Query("""
            SELECT ts FROM TravelStop ts
            JOIN FETCH ts.airport
            JOIN FETCH ts.flight f
            JOIN FETCH ts.shipment
            WHERE ts.stopStatus = 'PENDING'
              AND f.status = 'IN_FLIGHT'
              AND f.scheduledDeparture <= :horizon
              AND (f.scheduledArrival IS NULL OR f.scheduledArrival > :simulatedNow)
            ORDER BY f.id ASC, ts.shipment.id ASC, ts.stopOrder ASC
            """)
    List<TravelStop> findPendingStopsForActivation(@Param("simulatedNow") LocalDateTime simulatedNow,
                                                   @Param("horizon") LocalDateTime horizon);

    boolean existsByIdIsNotNull();

    boolean existsByShipmentId(Long shipmentId);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByFlight(Flight flight);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByStopStatusInAndShipmentStatusInOrderByShipmentIdAscStopOrderAsc(List<StopStatus> stopStatuses,
                                                                                            List<com.tasfb2b.model.ShipmentStatus> shipmentStatuses);

    /**
     * Cuenta las paradas completadas de un envío
     * (usado para calcular progressPercentage).
     */
    @Query("""
            SELECT COUNT(ts) FROM TravelStop ts
            WHERE ts.shipment = :shipment
              AND ts.stopStatus = 'COMPLETED'
            """)
    long countCompletedByShipment(@Param("shipment") Shipment shipment);

    @Query("""
            SELECT COUNT(ts) FROM TravelStop ts
            WHERE ts.stopStatus IN ('PENDING', 'IN_TRANSIT')
              AND ts.shipment.registrationDate >= :from
              AND ts.shipment.registrationDate < :to
            """)
    long countIncompleteByShipmentRegistrationBetween(@Param("from") java.time.LocalDateTime from,
                                                      @Param("to") java.time.LocalDateTime to);

    @Query("""
            SELECT COUNT(ts) FROM TravelStop ts
            WHERE ts.stopStatus = 'IN_TRANSIT'
              AND ts.shipment.registrationDate >= :from
              AND ts.shipment.registrationDate < :to
            """)
    long countInTransitByShipmentRegistrationBetween(@Param("from") java.time.LocalDateTime from,
                                                     @Param("to") java.time.LocalDateTime to);

    long countByStopStatus(StopStatus stopStatus);

    /**
     * Última parada completada de un envío.
     */
    @Query("""
            SELECT ts FROM TravelStop ts
            WHERE ts.shipment = :shipment
              AND ts.stopStatus = 'COMPLETED'
            ORDER BY ts.stopOrder DESC
            """)
    List<TravelStop> findCompletedStopsDesc(@Param("shipment") Shipment shipment);

    /**
     * Próxima parada pendiente del envío.
     */
    @Query("""
            SELECT ts FROM TravelStop ts
            WHERE ts.shipment = :shipment
              AND ts.stopStatus IN ('PENDING', 'IN_TRANSIT')
            ORDER BY ts.stopOrder ASC
            """)
    List<TravelStop> findNextStops(@Param("shipment") Shipment shipment);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TravelStop ts")
    int deleteAllFast();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "TRUNCATE TABLE travel_stop RESTART IDENTITY", nativeQuery = true)
    void truncateFast();
}
