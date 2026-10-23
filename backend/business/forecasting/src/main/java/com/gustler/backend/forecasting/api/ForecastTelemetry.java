package com.gustler.backend.forecasting.api;

import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
import java.time.Instant;
import java.util.List;

/** 선택적인 관측 수신자. 업무 저장소를 다시 조회하지 않는다. */
public interface ForecastTelemetry {
    ForecastTelemetry NONE = new ForecastTelemetry() {
    };

    default void pending(final long routeVersionId, Instant oldestObservedAt) {
    }

    default void settled(List<SettledEvaluation> results) {
    }

    default void published(
        final long routeId,
        final long routeVersionId,
        Instant observedAt,
        final int predictions
    ) {
    }
}
