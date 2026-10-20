package com.gustler.backend.observations.api;

/** 관측 변경 트랜잭션에 커밋 후 캐시 반영을 등록한다. 별도 전달 기록은 저장하지 않는다. */
public interface ObservationDeliveryHook {
    /**
     * 저장 완료된 관측 묶음을 전달 경계에 알린다. 롤백한 수집이 전달되지 않도록 호출 트랜잭션과 함께 사용한다.
     */
    void record(ObservationDeliveryBatch batch);
}
