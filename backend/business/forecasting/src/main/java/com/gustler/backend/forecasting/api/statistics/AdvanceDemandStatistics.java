package com.gustler.backend.forecasting.api.statistics;

/**
 * 원본 표본부터 발행까지 통계 처리의 다음 단계를 진행하는 공개 업무 경계다.
 */
public interface AdvanceDemandStatistics {
    /**
     * 미완료 통계 작업을 제한된 단계만 진행한다. 한 번 호출했다고 모든 노선의 재계산이 끝나는 것은 아니다.
     */
    void advance();
}
