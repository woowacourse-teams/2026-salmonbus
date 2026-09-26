package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
