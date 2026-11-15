package com.gustler.backend.observations.infrastructure.jpa;

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

    /**
     * 응답·관측·예보 실행 기록이 없는 미완료 배치의 상태만 종료로 바꾼다. 예보 실행은 기본 키(배치 예약 시각의 한국 날짜, 배치)로
     * 찾는다.
     */
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
              SELECT 1 FROM forecast_run run
              WHERE run.source_date = (batch.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                AND run.observation_batch_id = batch.id
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
              SELECT 1 FROM forecast_run run
              WHERE run.source_date = (batch.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                AND run.observation_batch_id = batch.id
          )
        ORDER BY batch.scheduled_at, batch.id
        LIMIT :limit
        FOR UPDATE OF batch SKIP LOCKED
        """, nativeQuery = true)
    List<Long> lockUnfinished(@Param("limit") int limit);
}
