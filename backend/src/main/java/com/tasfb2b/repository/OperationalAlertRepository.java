package com.tasfb2b.repository;

import com.tasfb2b.model.OperationalAlert;
import com.tasfb2b.model.OperationalAlertStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OperationalAlertRepository extends JpaRepository<OperationalAlert, Long> {

    List<OperationalAlert> findByStatusInOrderByIdDesc(List<OperationalAlertStatus> statuses);

    @Query("""
            SELECT a FROM OperationalAlert a
            JOIN a.shipment s
            WHERE a.status IN :statuses
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            ORDER BY a.id DESC
            """)
    List<OperationalAlert> findByStatusInAndShipmentRegistrationDateBetweenOrderByIdDesc(
            @Param("statuses") List<OperationalAlertStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("""
            SELECT COUNT(a) FROM OperationalAlert a
            JOIN a.shipment s
            WHERE a.status IN :statuses
              AND s.registrationDate >= :from
              AND s.registrationDate < :to
            """)
    long countByStatusInAndShipmentRegistrationDateBetween(
            @Param("statuses") List<OperationalAlertStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    long countByStatusIn(List<OperationalAlertStatus> statuses);

    Optional<OperationalAlert> findFirstByShipmentIdAndTypeAndStatusInOrderByIdDesc(Long shipmentId,
                                                                                     String type,
                                                                                     List<OperationalAlertStatus> statuses);

    boolean existsByIdIsNotNull();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "TRUNCATE TABLE operational_alert RESTART IDENTITY", nativeQuery = true)
    void truncateFast();
}
