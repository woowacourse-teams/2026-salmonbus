package com.gustler.backend.forecasting.api;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.time.Instant;
import java.util.List;

/** 선택적인 관측 수신자. 업무 저장소를 다시 조회하지 않는다. */
public interface ForecastTelemetry {
    ForecastTelemetry NONE = new ForecastTelemetry() {
    };

    default void collectionSeatQuality(final int reviews, final int outliers) {
    }

    default void pending(final long routeVersionId, Instant oldestObservedAt) {
    }

    /**
     * 채점이 예측을 닫고 그 결과 줄을 새로 저장했다고 알린다. 저장을 커밋한 뒤에 부르며 한 예측은 한 번만 알린다.
     *
     * @param closed 새로 저장한 줄로 닫힌 예측
     */
    default void closed(List<ForecastClosed> closed) {
    }

    /**
     * 노선의 배치 하나를 예보하고 커밋했다고 알린다. 예측이 없는 커밋도 알린다.
     *
     * @param observedAt 배치의 응답 수신 시각
     * @param predictions 커밋한 (관측, 목표 정류장) 예측 수
     */
    default void published(
        final long routeId,
        final long routeVersionId,
        Instant observedAt,
        final int predictions
    ) {
    }
}
