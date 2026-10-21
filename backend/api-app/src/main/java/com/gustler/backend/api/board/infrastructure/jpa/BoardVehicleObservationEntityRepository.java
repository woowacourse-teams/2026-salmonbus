package com.gustler.backend.api.board.infrastructure.jpa;

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
}
