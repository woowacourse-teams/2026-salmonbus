package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.Objects;

/** 차량의 전체 예보 거리를 평가 대상으로 선택했던 관측. */
public record EvaluationSamplingPoint(
    long routeVersionId,
    long modelDeploymentId,
    String vehicleId,
    int passedStopOrder,
    String direction,
    Instant observedAt
) {
    public EvaluationSamplingPoint {
        if (routeVersionId <= 0 || modelDeploymentId <= 0 || passedStopOrder < 0) {
            throw new IllegalArgumentException("평가 표본에는 유효한 노선 버전, 모델, 정류장 순번이 필요하다");
        }
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("평가 표본에는 차량 식별자가 필요하다");
        }
        Objects.requireNonNull(direction);
        Objects.requireNonNull(observedAt);
    }
}
