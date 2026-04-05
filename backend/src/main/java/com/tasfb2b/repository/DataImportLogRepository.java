package com.tasfb2b.repository;

import com.tasfb2b.model.DataImportLog;
import com.tasfb2b.model.ImportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataImportLogRepository extends JpaRepository<DataImportLog, Long> {

    List<DataImportLog> findByStatusOrderByImportedAtDesc(ImportStatus status);

    List<DataImportLog> findTop10ByOrderByImportedAtDesc();
}
