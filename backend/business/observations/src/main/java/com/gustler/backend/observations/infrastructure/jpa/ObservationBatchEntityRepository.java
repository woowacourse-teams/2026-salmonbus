package com.gustler.backend.observations.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationBatchEntityRepository extends JpaRepository<ObservationBatchJpaEntity, Long> {
    Optional<ObservationBatchJpaEntity> findByRouteVersionIdAndAttemptKey(long routeVersionId, String attemptKey);
}
