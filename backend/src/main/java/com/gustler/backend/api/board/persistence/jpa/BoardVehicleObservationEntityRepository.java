package com.gustler.backend.api.board.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BoardVehicleObservationEntityRepository
    extends Repository<VehicleObservationJpaEntity, Long> {

    /**
     * 그 묶음의 관측을 예보 유무와 무관하게 전부 읽는다.
     *
     * <p>예보 표에서 출발해 조인해 들어가면 예보 행이 없는 차량이 결과에 안 들어온다. 그래서
     * 관측에서 출발한다.
     */
    @Query("""
        SELECT observation
        FROM BoardVehicleObservationJpaEntity observation
        WHERE observation.observationBatch.id = :batchId
        """)
    List<VehicleObservationJpaEntity> findAllByBatchId(
        @Param("batchId") long batchId
    );
}
