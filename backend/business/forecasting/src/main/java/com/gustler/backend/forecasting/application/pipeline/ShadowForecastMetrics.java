package com.gustler.backend.forecasting.application.pipeline;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 그림자 실행의 지표다. 이름은 {@code salmonbus.pipeline.shadow.*}로 모은다. 지표 등록기가 없으면 아무것도 하지 않는다.
 *
 * <ul>
 *   <li>{@code predictions{result}}: (관측, 목표) 예측의 대조 분류별 수</li>
 *   <li>{@code batches{result}}: 배치의 처리 결과별 수</li>
 *   <li>{@code dropped}: 줄이 가득 차 버린 일의 수</li>
 *   <li>{@code replays{result}}: 노선 상태 되감기의 성공·실패 수</li>
 *   <li>{@code state.routes}, {@code state.observations}, {@code state.events}: 들고 있는 상태의 크기(게이지)</li>
 * </ul>
 */
final class ShadowForecastMetrics {
    private static final String PREFIX = "salmonbus.pipeline.shadow.";
    private final MeterRegistry meters;

    ShadowForecastMetrics(MeterRegistry meters, RouteStateRegistry states) {
        this.meters = meters;
        if (meters == null) {
            return;
        }
        Gauge.builder(PREFIX + "state.routes", states, RouteStateRegistry::routes).strongReference(true).register(meters);
        Gauge.builder(PREFIX + "state.observations", states, RouteStateRegistry::observations).strongReference(true)
            .register(meters);
        Gauge.builder(PREFIX + "state.events", states, RouteStateRegistry::events).strongReference(true).register(meters);
    }

    void predictions(ShadowForecastComparison comparison) {
        for (ShadowForecastComparison.Result result : ShadowForecastComparison.Result.values()) {
            final int count = comparison.count(result);
            if (count > 0 && meters != null) {
                meters.counter(PREFIX + "predictions", "result", result.tag()).increment(count);
            }
        }
    }

    void batch(String result) {
        if (meters != null) {
            meters.counter(PREFIX + "batches", "result", result).increment();
        }
    }

    void dropped() {
        if (meters != null) {
            meters.counter(PREFIX + "dropped").increment();
        }
    }

    void replay(final boolean succeeded) {
        if (meters != null) {
            meters.counter(PREFIX + "replays", "result", succeeded ? "succeeded" : "failed").increment();
        }
    }
}
