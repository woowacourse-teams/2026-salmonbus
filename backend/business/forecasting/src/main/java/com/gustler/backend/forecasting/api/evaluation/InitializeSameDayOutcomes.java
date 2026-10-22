package com.gustler.backend.forecasting.api.evaluation;

/**
 * 당일 보정에 필요한 저장 합계를 노선별로 초기화하는 공개 업무 경계다.
 */
public interface InitializeSameDayOutcomes {
    void initializeNext();
}
