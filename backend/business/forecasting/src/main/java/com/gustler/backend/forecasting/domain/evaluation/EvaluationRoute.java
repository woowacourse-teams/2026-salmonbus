package com.gustler.backend.forecasting.domain.evaluation;

/** 평가 대상이 남아 있는 노선과 노선 버전. */
public record EvaluationRoute(long routeId, long routeVersionId) {
}
