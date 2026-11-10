package com.gustler.backend.observations.infrastructure.jpa;

import java.time.OffsetDateTime;
import java.util.List;
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE observation_batch batch
        SET outcome = CASE batch.outcome
            WHEN 'RESERVED' THEN 'ABANDONED_BEFORE_SEND'
            WHEN 'DISPATCHING' THEN 'UNKNOWN_AFTER_DISPATCH'
        END
        WHERE batch.id = :id
          AND batch.outcome IN ('RESERVED', 'DISPATCHING')
          AND batch.response_received_at IS NULL
          AND batch.input_confirmed_at IS NULL
          AND batch.completed_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM vehicle_observation observation WHERE observation.observation_batch_id = batch.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM forecast_publication publication WHERE publication.source_batch_id = batch.id
          )
        """, nativeQuery = true)
    int closeUnfinished(@Param("id") long batchId);

    @Query(value = """
        SELECT batch.id
        FROM observation_batch batch
        WHERE batch.outcome IN ('RESERVED', 'DISPATCHING')
          AND batch.response_received_at IS NULL
          AND batch.input_confirmed_at IS NULL
          AND batch.completed_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM vehicle_observation observation WHERE observation.observation_batch_id = batch.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM forecast_publication publication WHERE publication.source_batch_id = batch.id
          )
        ORDER BY batch.scheduled_at, batch.id
        LIMIT :limit
        FOR UPDATE OF batch SKIP LOCKED
        """, nativeQuery = true)
    List<Long> lockUnfinished(@Param("limit") int limit);
}
