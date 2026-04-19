package com.tasfb2b.repository;

import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.ShipmentAuditLog;
import com.tasfb2b.model.ShipmentAuditType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentAuditLogRepository extends JpaRepository<ShipmentAuditLog, Long> {

    List<ShipmentAuditLog> findByShipmentOrderByEventAtAsc(Shipment shipment);

    Optional<ShipmentAuditLog> findTopByShipmentOrderByEventAtDesc(Shipment shipment);

    boolean existsByIdIsNotNull();

    @Query("""
            SELECT COUNT(l) FROM ShipmentAuditLog l
            WHERE l.eventType = :eventType
              AND l.eventAt >= :from
              AND l.eventAt < :to
            """)
    long countByEventTypeAndPeriod(
            @Param("eventType") ShipmentAuditType eventType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "TRUNCATE TABLE shipment_audit_log RESTART IDENTITY", nativeQuery = true)
    void truncateFast();
}
