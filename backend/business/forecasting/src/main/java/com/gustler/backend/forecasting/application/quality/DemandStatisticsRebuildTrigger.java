package com.gustler.backend.forecasting.application.quality;

/**
 * 차량별 또는 노선 전체 재집계 필요를 요청으로 기록하는 경계다.
 */
public interface DemandStatisticsRebuildTrigger {
    /**
     * 해당 차량의 합계를 다시 계산하도록 요청한다. 노선 전체 요청이 있으면 그 범위와 함께 조율한다.
     */
    void requestVehicle(long routeVersionId, String vehicleId);

    /**
     * 노선 전체 합계가 다시 계산되도록 요청을 갱신한다. 실제 재계산은 별도 단계 실행에서 수행한다.
     */
    void requestRoute(long routeVersionId);
}
