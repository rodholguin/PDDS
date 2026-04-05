package com.tasfb2b.repository;

import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.StopStatus;
import com.tasfb2b.model.TravelStop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TravelStopRepository extends JpaRepository<TravelStop, Long> {

    /** Paradas de un envío, ordenadas por posición. */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipmentOrderByStopOrderAsc(Shipment shipment);

    /** Paradas de un envío (sin orden, para conteos rápidos). */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipment(Shipment shipment);

    /** Paradas pendientes de un envío. */
    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByShipmentAndStopStatus(Shipment shipment, StopStatus stopStatus);

    @EntityGraph(attributePaths = {"airport", "flight", "shipment"})
    List<TravelStop> findByStopStatusIn(List<StopStatus> statuses);

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
}
