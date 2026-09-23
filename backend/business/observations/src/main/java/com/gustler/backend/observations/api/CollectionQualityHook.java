package com.gustler.backend.observations.api;

import java.util.List;

/** 저장 전 품질 잠금과 저장 후 조사 등록에 참여한다. 두 호출 모두 수집 결과의 트랜잭션 안에서 실행한다. */
public interface CollectionQualityHook {
    void beforeRowsStored(long routeVersionId, List<ObservedSeatValue> rows);
    void observationsStored(VehicleObservationsStored stored);
}
