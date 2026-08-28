package com.gustler.backend.collector.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectorVehicleObservationRepository extends JpaRepository<VehicleObservationJpaEntity, Long> {

    /**
     * 같은 계획을 다시 부를 때 지난 시도가 쌓아둔 관측을 비운다.
     * 묶음은 계획 하나에 한 행이라 그 안의 관측도 마지막 시도의 것이어야 한다.
     * 안 비우면 상류 행 번호가 겹쳐 ux_observation_source_row 에 걸린다.
     */
    @Modifying
    @Query("delete from CollectorVehicleObservation observation "
        + "where observation.observationBatchId = :observationBatchId")
    void deleteByObservationBatchId(
        @Param("observationBatchId") long observationBatchId
    );
}
