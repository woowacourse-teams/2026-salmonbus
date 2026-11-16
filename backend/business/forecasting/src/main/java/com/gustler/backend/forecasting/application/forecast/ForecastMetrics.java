package com.gustler.backend.forecasting.application.forecast;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * 예보의 지표다. 이름은 {@code salmonbus.forecast.*}로 모은다.
 * 지표 등록기가 없으면 아무것도 하지 않는다. 줄 길이는 실행하는 앱의 줄이 {@code salmonbus.forecast.queue}로 낸다.
 *
 * <ul>
 *   <li>{@code confirmation.lag}: 수신에서 착수까지의 시간(분포)</li>
 *   <li>{@code batch.duration}: 배치 하나의 처리 시간(분포)</li>
 *   <li>{@code batches{result}}: 결과별 배치 수. 실행 결과({@code published}, {@code empty}, {@code late}, {@code out_of_scope},
 *       {@code input_unavailable})와 저장하지 않은 사유({@code already_processed}, {@code stale}, {@code duplicate},
 *       {@code no_model}, {@code inactive_version}, {@code not_covered}, {@code reference_mismatch},
 *       {@code unsupported_profile}, {@code missing}, {@code not_success}, {@code failed}), 되감기 사유
 *       ({@code replay_deferred}, {@code replay_failed}), 순서를 지키려고 미룬 사유({@code retried}: 예외로 실패해 그 자리에서
 *       바로 다시 처리함, {@code out_of_order}: 더 늦게 수신한 배치가 이미 이력에 있음). {@code failed}는 다시 처리해도 실패한
 *       것만 센다. 모델 문맥이 없는 사유({@code no_model}, {@code inactive_version}, {@code not_covered},
 *       {@code reference_mismatch}, {@code unsupported_profile})는 배치를 읽지 않으므로 훑기마다 한 번 센다</li>
 *   <li>{@code replays{result}}: 노선 상태 되감기의 성공·실패 수</li>
 *   <li>{@code replay.extensions{result}}: 되감은 상태가 모자라 창을 늘려 다시 되감은 수({@code resolved},
 *       {@code unresolved}, {@code failed})</li>
 *   <li>{@code excluded{reason}}: 예보에서 뺀 차량 관측 수. 되감은 직후라 뺀 것({@code TRIP_START_UNKNOWN},
 *       {@code REPLAY_HISTORY_INCOMPLETE})과 정원을 모르거나 정원이 모델 범위 밖이라 뺀 것({@code VEHICLE_CAPACITY_UNKNOWN},
 *       {@code VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE})이다</li>
 *   <li>{@code passes.unattributed}: 연결 시작을 보지 못한 차량이 닫아 저장하지 않은 통과 수</li>
 *   <li>{@code passes.recovered}: 예보 단계를 돌리지 못하고 끝난 배치나 이전 배포본이 처리한 배치가 닫은 통과를 되감으면서 찾아
 *       저장한 수</li>
 *   <li>{@code rejected}: 줄이 가득 차 넣지 못한 훑기 수. 다음 신호나 줍기가 다시 넣는다</li>
 *   <li>{@code unrouted}: 노선 열쇠를 아직 몰라 훑기를 넣지 않은 신호 수. 다음 줍기가 넣는다</li>
 *   <li>{@code state.routes}, {@code state.observations}, {@code state.events}: 들고 있는 상태의 크기(게이지)</li>
 * </ul>
 */
final class ForecastMetrics {
    private static final String PREFIX = "salmonbus.forecast.";
    private final MeterRegistry meters;
    private final Timer lag;
    private final Timer duration;

    ForecastMetrics(MeterRegistry meters, RouteStateRegistry states) {
        this.meters = meters;
        if (meters == null) {
            lag = null;
            duration = null;
            return;
        }
        lag = Timer.builder(PREFIX + "confirmation.lag").publishPercentileHistogram().register(meters);
        duration = Timer.builder(PREFIX + "batch.duration").publishPercentileHistogram().register(meters);
        Gauge.builder(PREFIX + "state.routes", states, RouteStateRegistry::routes)
            .strongReference(true)
            .register(meters);
        Gauge.builder(PREFIX + "state.observations", states, RouteStateRegistry::observations)
            .strongReference(true)
            .register(meters);
        Gauge.builder(PREFIX + "state.events", states, RouteStateRegistry::events)
            .strongReference(true)
            .register(meters);
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
            meters.counter(PREFIX + "replays", "result", replayResult(succeeded)).increment();
        }
    }

    private static String replayResult(final boolean succeeded) {
        if (succeeded) {
            return "succeeded";
        }
        return "failed";
    }

    void replayExtension(RouteStateReplayer.Extension extension) {
        if (meters != null && extension != RouteStateReplayer.Extension.NONE) {
            meters.counter(PREFIX + "replay.extensions", "result", extension.name().toLowerCase(Locale.ROOT))
                .increment();
        }
    }

    void excluded(String reason) {
        if (meters != null) {
            meters.counter(PREFIX + "excluded", "reason", reason).increment();
        }
    }

    void unattributedPasses(final int count) {
        if (meters != null && count > 0) {
            meters.counter(PREFIX + "passes.unattributed").increment(count);
        }
    }

    void recoveredPasses(final int count) {
        if (meters != null && count > 0) {
            meters.counter(PREFIX + "passes.recovered").increment(count);
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
