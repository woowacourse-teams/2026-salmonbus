package com.gustler.backend.observations.infrastructure.jpa;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 노선 판본과 수집 계획 식별로 같은 관측 배치를 찾는 JPA 경계다.
 */
public interface ObservationBatchEntityRepository extends JpaRepository<ObservationBatchJpaEntity, Long> {
    Optional<ObservationBatchJpaEntity> findByRouteVersionIdAndAttemptKey(long routeVersionId, String attemptKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE observation_batch SET input_confirmed_at=:at WHERE id=:id AND input_confirmed_at IS NULL",
        nativeQuery = true)
    int confirmInputOnce(@Param("id") long batchId, @Param("at") OffsetDateTime at);
}
