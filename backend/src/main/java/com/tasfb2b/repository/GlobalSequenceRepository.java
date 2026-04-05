package com.tasfb2b.repository;

import com.tasfb2b.model.GlobalSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface GlobalSequenceRepository extends JpaRepository<GlobalSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM GlobalSequence s WHERE s.sequenceName = :name")
    Optional<GlobalSequence> findByNameForUpdate(@Param("name") String name);
}
