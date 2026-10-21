package com.gustler.backend.observations.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 수집 배치별 차량 관측을 저장·조회·삭제하는 JPA 경계다.
 */
public interface VehicleObservationEntityRepository extends JpaRepository<VehicleObservationJpaEntity, Long> {
    /**
     * 같은 계획을 다시 호출할 때 지난 시도에서 저장한 관측을 비운다.
     * 묶음은 계획 하나에 한 행이므로 그 안의 관측도 마지막 시도의 것이어야 한다.
     * 비우지 않으면 상류 행 번호가 겹쳐 ux_observation_source_row 제약에 걸린다.
     */
    @Modifying
    @Query("delete from VehicleObservation observation "
        + "where observation.observationBatchId = :observationBatchId")
    void deleteByObservationBatchId(
        @Param("observationBatchId") long observationBatchId
    );
}
