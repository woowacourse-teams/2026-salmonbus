package com.gustler.backend.api.vehicle.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 지정한 수집 배치의 차량 관측을 읽거나 저장 모듈에서 배치 행을 정리하는 JPA 경계다.
 */
public interface VehicleObservationEntityRepository
    extends Repository<VehicleObservationJpaEntity, Long> {
    @Query("""
        SELECT observation
        FROM VehicleObservationJpaEntity observation
        JOIN FETCH observation.routeStop stop
        WHERE observation.observationBatch.id = :batchId
        ORDER BY CASE
                     WHEN stop.direction = com.gustler.backend.api.vehicle.domain.VehicleDirection.UP
                     THEN 0
                     ELSE 1
                 END,
                 observation.stopOrder,
                 observation.sourceRowNumber
        """)
    List<VehicleObservationJpaEntity> findAllByBatchId(
        @Param("batchId") final long batchId
    );
}
