package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import com.gustler.backend.forecasting.application.publication.LegacyForecastListener;
import com.gustler.backend.forecasting.application.publication.LegacyForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 옛 경로({@code forecast.pipeline=v1})가 확정 판본 배치 하나를 끝낼 때마다 같은 배치를 새 경로로 다시 계산하는 그림자 실행이다.
 *
 * <p>옛 경로의 예보 스레드에서는 {@link #batchFinished}가 일을 {@link ShadowForecastFeed}에 넣기만 한다. 넣기가 실패하면 그 노선의
 * 상태를 버리라고 표시하고 버린 수를 센다. 한 건의 처리는 그림자 전용 스레드에서 다음 순서로 한다. 2~5번은 본선
 * ({@link ForecastPipelineService})과 같은 처리 코드({@link PipelineBatchProcessor})다.
 * <ol>
 *   <li>배치를 저장된 줄 전부와 함께 다시 읽는다({@link ObservationBatchReader}, 되감기와 같은 조회).</li>
 *   <li>옛 경로가 확정하지 않은 배치는 상태에 넣지 않고 그 사유({@code LATE}, {@code EMPTY})만 실행 줄로 남긴다.</li>
 *   <li>노선 상태가 없으면 이 배치 직전까지 되감아 만든다({@link RouteStateReplayer}). 되감기가 실패하면 그 노선은 착수 시각 기준
 *       {@value #REPLAY_RETRY_MINUTES}분 동안 되감지 않고 예보 없이 실행 줄만 남긴다.</li>
 *   <li>{@link ForecastStage#advance}로 상태를 전진시키고, 옛 경로와 같은 예측 조립 객체({@link AlightingAwarePredictionAssembler})로
 *       예측을 만든다. 출퇴근 범위 판정은 옛 경로의 입력 조회와 같은 기준이다.</li>
 *   <li>예측 실행·버스별 기록·닫힌 통과를 한 트랜잭션으로 쓰고, 커밋한 뒤에만 상태를 바꾼다.</li>
 *   <li>옛 예측과 (관측, 목표)마다 비트 단위로 대조해 지표·경고 로그를 남기고, 사건을 {@link PipelineEvents}에 넘긴다.</li>
 * </ol>
 * 어느 단계든 실패하면 로그와 지표를 남기고 그 노선의 상태를 버린다. 다음 배치가 되감아 다시 만든다.
 *
 * <p>착수 시각은 옛 경로가 {@code observation_batch.input_confirmed_at}에 남긴 값을 쓴다. 옛 경로의 입력 조회와 되감기가 모두 이
 * 값을 읽으므로, 넘겨받은 {@code generatedAt}이 아니라 이 값을 써야 이어 온 상태와 되감은 상태가 같다. 두 값은 첫 처리에서는
 * 마이크로초 반올림만큼 다르고, 옛 경로가 같은 배치를 다시 처리하면 더 벌어진다. {@code route} 행 잠금을 잡지 않고
 * {@code observation_batch}를 고치지 않는다.
 */
public final class ShadowForecastService implements LegacyForecastListener {
    /** 한 건의 처리 시간을 남기는 {@link WorkerOperationLog} 작업 이름. */
    public static final String OPERATION = "pipeline_shadow_batch";
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowForecastService.class);
    private static final Duration WARNING_WINDOW = Duration.ofMinutes(1);
    /** 되감기가 실패한 노선을 다시 되감기까지 기다리는 시간. 배치의 착수 시각 기준이다. */
    static final long REPLAY_RETRY_MINUTES = PipelineBatchProcessor.REPLAY_RETRY_MINUTES;
    private static final int WARNINGS_PER_WINDOW = 20;

    private final ShadowForecastFeed feed;
    private final ObservationBatchReader batches;
    private final RouteStateRegistry states;
    private final PipelineBatchProcessor processor;
    private final Clock clock;
    private final ShadowForecastMetrics metrics;
    private Instant warningWindowStart = Instant.EPOCH;
    private int warningsInWindow;

    /**
     * @param rushOnly 옛 경로의 {@code forecast.alighting.rush-only}. 시간 확장 모델이 없을 때 출퇴근 범위 밖 배치를 예보하지 않는다
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public ShadowForecastService(
        ShadowForecastFeed feed,
        ForecastStage stage,
        RouteStateReplayer replayer,
        ObservationBatchReader batches,
        ForecastRecordStore records,
        StopPassStore passes,
        TransactionOperations transactions,
        List<PipelineEvents> listeners,
        RouteStateRegistry states,
        final boolean rushOnly,
        Clock clock,
        MeterRegistry meters
    ) {
        this.feed = Objects.requireNonNull(feed);
        this.batches = Objects.requireNonNull(batches);
        this.states = Objects.requireNonNull(states);
        this.processor = new PipelineBatchProcessor("pipeline_shadow", stage, replayer, records, passes, transactions,
            listeners, states, rushOnly);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = new ShadowForecastMetrics(meters, states);
    }

    /** 옛 경로의 스레드에서 불린다. 일을 줄에 넣기만 하고, 실패해도 예외를 밖으로 내지 않는다. */
    @Override
    public void batchFinished(LegacyForecastResult result) {
        Task task = new Task(result);
        try {
            feed.submit(task);
        } catch (RuntimeException rejected) {
            task.discard();
        }
    }

    /** 들고 있는 노선 상태의 등록부. */
    public RouteStateRegistry states() {
        return states;
    }

    /**
     * 배치 하나를 처리한다. 그림자 전용 스레드에서만 부른다. 예외를 던지지 않는다.
     *
     * @param legacy 옛 경로의 결과
     * @return 이 배치의 처리 결과 분류({@code salmonbus.pipeline.shadow.batches}의 태그)
     */
    String process(LegacyForecastResult legacy) {
        AlightingAwareRouteStatistics statistics = legacy.statistics();
        try {
            if (!statistics.confirmedProfile()) {
                return count("unsupported_profile");
            }
            Optional<ObservationBatchRead> found = batches.readBatch(legacy.batch().observationBatchId());
            if (found.isEmpty()) {
                return count("missing");
            }
            return process(legacy, found.get());
        } catch (RuntimeException failure) {
            states.remove(statistics.route());
            LOGGER.warn("event=pipeline_shadow_failed route={} batchId={} exceptionType={}", statistics.route(),
                legacy.batch().observationBatchId(), failure.getClass().getSimpleName(), failure);
            return count("failed");
        }
    }

    private String process(LegacyForecastResult legacy, ObservationBatchRead read) {
        RouteContext context = new RouteContext(legacy.statistics(), legacy.stops(), legacy.runtime());
        PipelineBatchProcessor.Processed processed = processor.process(context, read, legacy.generatedAt(), false);
        if (processed.replayed() != null) {
            metrics.replay(processed.replayed());
        }
        if (processed.status() == PipelineBatchProcessor.Status.STALE) {
            return count("stale");
        }
        ForecastOutcome outcome = processed.published().outcome();
        ShadowForecastComparison comparison = ShadowForecastComparison.compare(legacy.predictions(),
            processed.vehicles(), processed.excluded());
        metrics.predictions(comparison);
        String result = processed.special() != null ? processed.special() : classify(legacy, outcome, comparison);
        if (comparison.mismatches() > 0 || "mismatch".equals(result)) {
            warnMismatch(legacy, outcome, comparison);
        }
        return count(result);
    }

    /** 옛 결과와 새 결과가 같은 뜻인지와 예측 대조로 배치를 분류한다. */
    private static String classify(LegacyForecastResult legacy, ForecastOutcome outcome, ShadowForecastComparison comparison) {
        final boolean sameMeaning = switch (legacy.kind()) {
            case PUBLISHED -> outcome == (legacy.predictions().isEmpty() ? ForecastOutcome.EMPTY : ForecastOutcome.PUBLISHED);
            case NOT_CONFIRMED -> outcome == ForecastOutcome.LATE || outcome == ForecastOutcome.EMPTY;
            case OUT_OF_SCOPE -> outcome == ForecastOutcome.OUT_OF_SCOPE;
            case FAILED -> outcome == ForecastOutcome.INPUT_UNAVAILABLE;
        };
        if (comparison.mismatches() > 0) {
            return "mismatch";
        }
        if (sameMeaning) {
            return "match";
        }
        return comparison.count(ShadowForecastComparison.Result.EXCLUDED) > 0 ? "excluded" : "mismatch";
    }

    private void warnMismatch(LegacyForecastResult legacy, ForecastOutcome outcome, ShadowForecastComparison comparison) {
        if (!allowWarning()) {
            return;
        }
        LOGGER.warn("event=pipeline_shadow_mismatch route={} batchId={} legacy={} outcome={} valueDiffers={} "
                + "legacyOnly={} newOnly={} excluded={}", legacy.statistics().route(),
            legacy.batch().observationBatchId(), legacy.kind(), outcome,
            comparison.count(ShadowForecastComparison.Result.VALUE_DIFFERS),
            comparison.count(ShadowForecastComparison.Result.LEGACY_ONLY),
            comparison.count(ShadowForecastComparison.Result.NEW_ONLY),
            comparison.count(ShadowForecastComparison.Result.EXCLUDED));
        for (ShadowForecastComparison.Difference difference : comparison.differences()) {
            LOGGER.warn("event=pipeline_shadow_value_differs batchId={} observationId={} target={} legacySeats={} "
                    + "newSeats={} legacyProbability={} newProbability={}", legacy.batch().observationBatchId(),
                difference.observationId(), difference.target(), difference.legacySeats(), difference.newSeats(),
                difference.legacyProbability(), difference.newProbability());
        }
    }

    private boolean allowWarning() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!now.isBefore(warningWindowStart.plus(WARNING_WINDOW))) {
            warningWindowStart = now;
            warningsInWindow = 0;
        }
        return warningsInWindow++ < WARNINGS_PER_WINDOW;
    }

    private String count(String result) {
        metrics.batch(result);
        return result;
    }

    private void discard(LegacyForecastResult result) {
        states.invalidate(result.statistics().route());
        metrics.dropped();
        LOGGER.warn("event=pipeline_shadow_dropped route={} batchId={}", result.statistics().route(),
            result.batch().observationBatchId());
    }

    private final class Task implements ShadowForecastTask {
        private final LegacyForecastResult result;

        private Task(LegacyForecastResult result) {
            this.result = result;
        }

        @Override
        public long observationBatchId() {
            return result.batch().observationBatchId();
        }

        @Override
        public String route() {
            return result.statistics().route();
        }

        @Override
        public void run() {
            WorkerOperationLog.run(OPERATION, result.batch().routeVersionId(), () -> process(result));
        }

        @Override
        public void discard() {
            ShadowForecastService.this.discard(result);
        }
    }
}
