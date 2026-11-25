package com.gustler.backend.observations.api;

/** 관측 저장 트랜잭션에서 좌석 품질을 판정한다. */
public interface ObservationSeatQualityChecker {

    /** 빈 응답에도 호출한다. 양수 좌석이 없으면 실제 좌석 판정 구현이 DB 조사를 생략한다. */
    void observationsStored(long batchId, long routeVersionId, boolean hasPositiveSeats);
}
