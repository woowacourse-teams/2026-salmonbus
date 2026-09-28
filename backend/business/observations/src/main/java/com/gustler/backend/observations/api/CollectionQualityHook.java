package com.gustler.backend.observations.api;

/** 관측 저장 뒤 품질 조사 등록에 참여한다. 수집 결과의 트랜잭션 안에서 실행한다. */
public interface CollectionQualityHook {
    void observationsStored(VehicleObservationsStored stored);
}
