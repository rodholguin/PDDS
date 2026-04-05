package com.tasfb2b.repository;

import com.tasfb2b.model.OperationalAlert;
import com.tasfb2b.model.OperationalAlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperationalAlertRepository extends JpaRepository<OperationalAlert, Long> {

    List<OperationalAlert> findByStatusInOrderByIdDesc(List<OperationalAlertStatus> statuses);

    long countByStatusIn(List<OperationalAlertStatus> statuses);
}
