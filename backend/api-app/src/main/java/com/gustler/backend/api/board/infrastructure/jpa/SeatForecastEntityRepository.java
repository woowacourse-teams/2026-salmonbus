package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 지정한 발행 배치의 좌석 예보 행을 읽는 JPA 경계다.
 */
public interface SeatForecastEntityRepository
    extends Repository<SeatForecastJpaEntity, SeatForecastJpaId> {
    @Query("""
        SELECT forecast
        FROM BoardSeatForecastJpaEntity forecast
        JOIN FETCH forecast.vehicleObservation observation
        JOIN FETCH forecast.modelDeployment
        WHERE forecast.publication.id = (
            SELECT publication.id
            FROM BoardForecastPublicationJpaEntity publication
            WHERE publication.sourceBatchId = :batchId
        )
          AND observation.observationBatch.id = :batchId
        """)
    List<SeatForecastJpaEntity> findAllByBatchId(@Param("batchId") long batchId);

    /**
     * 여러 발행의 예보 행을 발행 ID, 차량 관측 묶음 ID와 함께 읽는다.
     *
     * <p>{@link #findAllByBatchId}처럼 발행 ID와 관측 묶음으로 범위를 좁힌다. 발행의 기준 묶음과 차량 관측 묶음이
     * 같은 행만 고르는 일은 발행 ID와 기준 묶음의 짝을 아는 호출하는 쪽이 한다.
     */
    @Query("""
        SELECT forecast.publication.id, observation.observationBatch.id, forecast
        FROM BoardSeatForecastJpaEntity forecast
        JOIN FETCH forecast.vehicleObservation observation
        JOIN FETCH forecast.modelDeployment
        WHERE forecast.publication.id IN :publicationIds
          AND observation.observationBatch.id IN :batchIds
        """)
    List<Object[]> findAllByPublicationIdInAndBatchIdIn(
        @Param("publicationIds") Collection<Long> publicationIds,
        @Param("batchIds") Collection<Long> batchIds
    );
}
