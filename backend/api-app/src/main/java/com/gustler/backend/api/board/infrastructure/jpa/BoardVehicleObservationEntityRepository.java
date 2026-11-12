package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 탑승판의 기준 배치와 노선 판본에 속한 차량 관측을 읽는다.
 */
public interface BoardVehicleObservationEntityRepository extends Repository<VehicleObservationJpaEntity, Long> {
    @Query("""
        SELECT observation
        FROM BoardVehicleObservationJpaEntity observation
        WHERE observation.observationBatch.id = :batchId
          AND observation.routeVersionId = :routeVersionId
        """)
    List<VehicleObservationJpaEntity> findAllByBatchIdAndRouteVersionId(
        @Param("batchId") long batchId,
        @Param("routeVersionId") long routeVersionId
    );

    /**
     * 여러 관측 묶음과 노선 판본의 차량 관측을 관측 묶음 ID와 함께 읽는다. 각 행은 관측 묶음 ID, 차량 관측 순서다.
     * 묶음과 판본의 정확한 짝을 고르는 일은 호출하는 쪽이 한다.
     */
    @Query("""
        SELECT observation.observationBatch.id, observation
        FROM BoardVehicleObservationJpaEntity observation
        WHERE observation.observationBatch.id IN :batchIds
          AND observation.routeVersionId IN :routeVersionIds
        """)
    List<Object[]> findAllByBatchIdInAndRouteVersionIdIn(
        @Param("batchIds") Collection<Long> batchIds,
        @Param("routeVersionIds") Collection<Long> routeVersionIds
    );
}
