package com.gustler.backend.forecasting.api.publication;

/**
 * 지원 범위의 대기 관측으로 좌석 예보를 발행하는 공개 업무 경계다.
 */
public interface PublishPendingForecasts {
    /**
     * 발행 대기 관측을 노선별로 예측해 저장한다. 노선별 실패를 격리해 다른 노선 처리를 이어간다.
     */
    void writeForecasts();

    /**
     * 지정 노선 판본의 대기 관측을 예측해 발행한다. 품질 판본과 실제 커밋 경계는 저장 유스케이스에서 확인한다.
     */
    void writeForecastsOfRoute(long routeVersionId);
}
