package com.gustler.backend.api.route.domain;

/**
 * 현재 노선에서 예보를 제공할 준비 상태를 나타낸다.
 * 차량 부재와 모델·예보 준비 상태를 구분한다.
 */
public enum RouteStatus {
    FORECAST_READY,
    PREPARING,
    ;

    public static RouteStatus from(
        final boolean activeModelExists,
        final boolean forecastPublished
    ) {
        if (activeModelExists && forecastPublished) {
            return FORECAST_READY;
        }
        return PREPARING;
    }
}
