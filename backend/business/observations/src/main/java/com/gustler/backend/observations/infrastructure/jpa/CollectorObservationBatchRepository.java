package com.gustler.backend.observations.infrastructure.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectorObservationBatchRepository extends JpaRepository<ObservationBatchJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ObservationBatchJpaEntity> findByRouteVersionIdAndAttemptKey(long routeVersionId, String attemptKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from CollectorObservationBatch batch where batch.id = :id")
    Optional<ObservationBatchJpaEntity> lockById(@Param("id") long id);
}
