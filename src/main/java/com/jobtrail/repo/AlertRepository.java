package com.jobtrail.repo;

import com.jobtrail.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    List<Alert> findByAcknowledgedAtIsNullOrderByCreatedAtDesc();

    long countByAcknowledgedAtIsNull();

    List<Alert> findTop30ByOrderByCreatedAtDesc();

    @Modifying
    @Query("update Alert a set a.acknowledgedAt = :now where a.acknowledgedAt is null")
    int acknowledgeAll(@Param("now") Instant now);

    void deleteByApplicationId(Long applicationId);
}
