package com.gustler.backend.forecasting.application.forecast;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 채점의 지표다. 이름은 {@code salmonbus.scoring.*}로 모은다. 지표 등록기가 없으면 아무것도 하지 않는다.
 *
 * <ul>
 *   <li>{@code waiting}: 통과를 기다리는 예측 수(게이지). 채점 스레드가 일마다 갱신한다</li>
 *   <li>{@code pending.rows}: 쓰기를 기다리는 정답·미해결 줄 수(게이지)</li>
 *   <li>{@code results{kind,state,reason}}: 채점 결과 수. {@code kind}는 정답 줄({@code label}), 미해결 예측
 *       ({@code unresolved}), 정답에 짝지어진 예측({@code scored})이다. 되감기에서 다시 나온 결과는 세지 않는다</li>
 *   <li>{@code lag}: 통과가 닫힌 시각에서 채점 스레드가 그 통과를 처리한 시각까지</li>
 *   <li>{@code replays{reason,result}}: 노선 상태 되감기 수</li>
 *   <li>{@code dropped}: 줄이 가득 차 버린 사건 수, {@code skipped}: 되감기를 미뤄 처리하지 않은 사건 수</li>
 *   <li>{@code failures}: 사건 처리 중 예외로 노선 상태를 버린 수</li>
 *   <li>{@code write.failures}: 정답·미해결 쓰기 실패 수, {@code written{table}}: 새로 넣은 줄 수</li>
 * </ul>
 */
final class ScoringMetrics {

    private static final String PREFIX = "salmonbus.scoring.";

    private final MeterRegistry meters;
    private final AtomicInteger waiting = new AtomicInteger();
    private final Timer lag;

    ScoringMetrics(MeterRegistry meters, ScoringWriter writer) {
        this.meters = meters;
        if (meters == null) {
            lag = null;
            return;
        }
        Gauge.builder(PREFIX + "waiting", waiting, AtomicInteger::get).strongReference(true).register(meters);
        Gauge.builder(PREFIX + "pending.rows", writer, ScoringWriter::pendingCount).strongReference(true)
            .register(meters);
        lag = Timer.builder(PREFIX + "lag").register(meters);
    }

    void waiting(final int count) {
        waiting.set(count);
    }

    void results(ScoringResult result) {
        if (meters == null) {
            return;
        }
        Map<List<Object>, PassLabel> labels = new HashMap<>();
        for (PassLabel label : result.labels()) {
            labels.put(List.of(
                    label.sourceDate(),
                    label.routeId(),
                    label.tripStartObservationId(),
                    label.stopOrder(),
                    label.capacity(),
                    label.modelDeploymentId()
            ), label);
            count("label", label.state().name(), label.reason());
        }
        for (UnresolvedForecast closed : result.unresolved()) {
            count("unresolved", closed.state().name(), closed.reason());
        }
        for (ScoredForecast scored : result.scored()) {
            PassLabel label = labels.get(List.of(
                    scored.passSourceDate(),
                    scored.routeId(),
                    scored.tripStartObservationId(),
                    scored.stopOrder(),
                    scored.capacity(),
                    scored.modelDeploymentId()
            ));
            count("scored", labelState(label), labelReason(label));
        }
    }

    private static String labelState(PassLabel label) {
        if (label == null) {
            return "UNKNOWN";
        }
        return label.state().name();
    }

    private static String labelReason(PassLabel label) {
        if (label == null) {
            return null;
        }
        return label.reason();
    }

    void lag(Duration elapsed) {
        if (lag != null && !elapsed.isNegative()) {
            lag.record(elapsed);
        }
    }

    void replay(String reason, final boolean succeeded) {
        if (meters != null) {
            meters.counter(
                    PREFIX + "replays",
                    "reason",
                    reason,
                    "result",
                    replayResult(succeeded)
            )
                .increment();
        }
    }

    private static String replayResult(final boolean succeeded) {
        if (succeeded) {
            return "succeeded";
        }
        return "failed";
    }

    void dropped(final int count) {
        if (meters != null) {
            meters.counter(PREFIX + "dropped").increment(count);
        }
    }

    void skipped() {
        increment("skipped");
    }

    void failure() {
        increment("failures");
    }

    void writeFailure() {
        increment("write.failures");
    }

    void written(ScoringWriter.Flushed flushed) {
        if (meters == null) {
            return;
        }
        meters.counter(PREFIX + "written", "table", "pass_label").increment(flushed.labels());
        meters.counter(PREFIX + "written", "table", "forecast_unresolved").increment(flushed.unresolved());
    }

    private void count(String kind, String state, String reason) {
        meters.counter(
                PREFIX + "results",
                "kind",
                kind,
                "state",
                state,
                "reason",
                reasonTag(reason)
        )
            .increment();
    }

    private static String reasonTag(String reason) {
        if (reason == null) {
            return "none";
        }
        return reason;
    }

    private void increment(String name) {
        if (meters != null) {
            meters.counter(PREFIX + name).increment();
        }
    }
}
