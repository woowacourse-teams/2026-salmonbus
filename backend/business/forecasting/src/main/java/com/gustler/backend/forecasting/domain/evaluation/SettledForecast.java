package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;

/** 잔여석을 확인하고 평가가 확정된 예보 한 행으로, 당일 성적 집계에 사용한다. */
public record SettledForecast(
    long routeId,
    long modelDeploymentId,
    int stopsToTarget,
    double rawFullChance,
    Instant arrivedAt,
    int seatsOnArrival
) {
    public boolean wasFull() {
        return seatsOnArrival == 0;
    }
}
