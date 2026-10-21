package com.gustler.backend.observations.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 노선 판본과 수집 계획 식별로 같은 관측 배치를 찾는 JPA 경계다.
 */
public interface ObservationBatchEntityRepository extends JpaRepository<ObservationBatchJpaEntity, Long> {
    Optional<ObservationBatchJpaEntity> findByRouteVersionIdAndAttemptKey(long routeVersionId, String attemptKey);
}
