package com.gustler.backend.observations.api;

/**
 * 저장된 관측의 품질 판정 필요를 다른 업무 모듈에 전달한다.
 */
public interface CollectionQualityHook {
    /**
     * 저장된 관측에서 품질 조사 입력을 반영한다. 관측 저장과 같은 트랜잭션 경계에서 호출한다.
     */
    void observationsStored(VehicleObservationsStored stored);
}
