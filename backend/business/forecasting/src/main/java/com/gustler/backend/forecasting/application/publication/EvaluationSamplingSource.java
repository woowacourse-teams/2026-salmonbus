package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPoint;

/** 예보 계산 때 이미 읽은 차량 정보. 평가 선택을 위해 DB를 다시 읽지 않는다. */
public record EvaluationSamplingSource(long observationId, String sourceRouteId, EvaluationSamplingPoint point) {
}
