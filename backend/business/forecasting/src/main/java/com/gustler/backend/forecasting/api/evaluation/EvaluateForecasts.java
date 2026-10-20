package com.gustler.backend.forecasting.api.evaluation;

/**
 * 미완료 예보에 후속 도착 관측을 연결해 정산하는 공개 업무 경계다.
 */
public interface EvaluateForecasts {
    void settleArrivalLabels();
}
