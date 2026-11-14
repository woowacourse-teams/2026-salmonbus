package com.gustler.backend.forecasting.application.pipeline;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * 본선({@code forecast.pipeline=v2})의 지표다. 이름은 {@code salmonbus.pipeline.forecast.*}로 모으며 옛 경로의 지표와 겹치지 않는다.
 * 지표 등록기가 없으면 아무것도 하지 않는다. 줄 길이는 실행하는 앱의 줄이 {@code salmonbus.pipeline.forecast.queue}로 낸다.
 *
 * <ul>
 *   <li>{@code confirmation.lag}: 수신에서 착수까지의 시간(분포)</li>
 *   <li>{@code batch.duration}: 배치 하나의 처리 시간(분포)</li>
 *   <li>{@code batches{result}}: 결과별 배치 수. 실행 결과({@code published}, {@code empty}, {@code late}, {@code out_of_scope},
 *       {@code input_unavailable})와 저장하지 않은 사유({@code already_processed}, {@code stale}, {@code duplicate},
 *       {@code no_model}, {@code inactive_version}, {@code not_covered}, {@code reference_mismatch},
 *       {@code unsupported_profile}, {@code missing}, {@code not_success}, {@code failed}), 되감기 사유({@code replay_deferred},
 *       {@code replay_failed})</li>
 *   <li>{@code replays{result}}: 노선 상태 되감기의 성공·실패 수</li>
 *   <li>{@code excluded{reason}}: 되감은 직후라 예보에서 뺀 차량 관측 수({@code TRIP_START_UNKNOWN},
 *       {@code REPLAY_HISTORY_INCOMPLETE})</li>
 *   <li>{@code rejected}: 줄이 가득 차 넣지 못한 배치 수. 다음 줍기가 다시 넣는다</li>
 *   <li>{@code unrouted}: 노선 열쇠를 아직 몰라 신호로 넣지 않은 배치 수. 다음 줍기가 넣는다</li>
 *   <li>{@code state.routes}, {@code state.observations}, {@code state.events}: 들고 있는 상태의 크기(게이지)</li>
 * </ul>
 */
final class PipelineForecastMetrics {
    private static final String PREFIX = "salmonbus.pipeline.forecast.";
    private final MeterRegistry meters;
    private final Timer lag;
    private final Timer duration;

    PipelineForecastMetrics(MeterRegistry meters, RouteStateRegistry states) {
        this.meters = meters;
        if (meters == null) {
            lag = null;
            duration = null;
            return;
        }
        lag = Timer.builder(PREFIX + "confirmation.lag").publishPercentileHistogram().register(meters);
        duration = Timer.builder(PREFIX + "batch.duration").publishPercentileHistogram().register(meters);
        Gauge.builder(PREFIX + "state.routes", states, RouteStateRegistry::routes).strongReference(true).register(meters);
        Gauge.builder(PREFIX + "state.observations", states, RouteStateRegistry::observations).strongReference(true)
            .register(meters);
        Gauge.builder(PREFIX + "state.events", states, RouteStateRegistry::events).strongReference(true).register(meters);
    }

    void lag(Duration value) {
        if (lag != null && !value.isNegative()) {
            lag.record(value);
        }
    }

    void duration(Duration value) {
        if (duration != null) {
            duration.record(value);
        }
    }

    void batch(String result) {
        if (meters != null) {
            meters.counter(PREFIX + "batches", "result", result).increment();
        }
    }

    void replay(final boolean succeeded) {
        if (meters != null) {
            meters.counter(PREFIX + "replays", "result", succeeded ? "succeeded" : "failed").increment();
        }
    }

    void excluded(String reason) {
        if (meters != null) {
            meters.counter(PREFIX + "excluded", "reason", reason).increment();
        }
    }

    void rejected() {
        if (meters != null) {
            meters.counter(PREFIX + "rejected").increment();
        }
    }

    void unrouted() {
        if (meters != null) {
            meters.counter(PREFIX + "unrouted").increment();
        }
    }
}
