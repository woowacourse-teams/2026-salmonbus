package com.gustler.backend.observations.domain;

import com.gustler.backend.observations.domain.StoredObservations.Row;
import java.util.List;
import java.util.Optional;

/** 수집 배치와 해당 시도의 관측을 함께 저장한다. */
public interface CollectionBatchRepository {
    /**
     * 같은 노선 판본과 시도 키의 수집 묶음을 찾는다. 없으면 빈 값이며 새 묶음을 만들지 않는다.
     */
    Optional<CollectionBatch> findByPlan(CollectionPlan plan);

    /**
     * 이미 존재해야 하는 수집 묶음을 읽는다. 없는 식별자는 필수 조회 실패로 처리한다.
     */
    CollectionBatch getById(long batchId);

    /**
     * 수집 상태를 저장하고 식별자를 반환한다. 호출자가 여는 트랜잭션 안에서 할당량 예약과 함께 사용할 수 있다.
     */
    long save(CollectionBatch batch);

    /**
     * 지정 수집 묶음의 기존 관측을 제거한다. 재수신 결과를 저장하는 호출자의 트랜잭션에 참여한다.
     */
    void deleteObservationsOf(long batchId);

    /**
     * 정규화한 관측을 저장하고 저장 식별자가 붙은 행을 반환한다. 제외·미관측 상태를 임의의 정상 값으로 채우지 않는다.
     */
    List<Row> saveObservations(CollectionBatch batch, List<UpstreamObservationRow> rows);
}
