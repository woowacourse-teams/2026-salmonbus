package com.gustler.backend.forecasting.domain.statistics;

import java.util.List;

/**
 * 정산 통계 표본을 기록·잠금·처리 완료하는 경계다.
 * 원본 정산과 표본 대기를 구분한다.
 */
public interface DemandSampleStore {
    /**
     * 확정 평가에서 만든 통계 표본을 저장한다. 평가 확정과 같은 트랜잭션에서 호출한다.
     */
    void record(List<DemandSample> samples);

    /**
     * 차량별 입력 커서 이후부터 고정 상한까지 최대 limit건을 잠가 읽는다. 처리와 제거는 호출 트랜잭션이 묶는다.
     */
    DemandSamplePage lockPage(
        long routeVersionId,
        String vehicleId,
        long afterInputId,
        long inputUntilId,
        int limit
    );

    /**
     * 처리한 표본 식별자만 제거한다. 집계 반영과 같은 트랜잭션에서 호출한다.
     */
    void remove(List<Long> sampleIds);

    /**
     * 재구축 입력의 상한으로 고정할 현재 마지막 표본 식별자를 읽는다.
     */
    long lastSampleId();
}
