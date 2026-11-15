package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 새 예보 경로를 본선으로 돌린다({@code forecast.pipeline=v2}). 옛 발행 경로({@code PublishPendingForecastsService},
 * {@code ForecastBatchWriter})를 대신해 수집이 커밋한 배치를 처리한다.
 *
 * <p>배치는 노선별 실행 줄({@link ForecastLanes})에서 처리한다. 줄 열쇠는 노선 판본의 GBIS 노선 ID다. 노선 상태의 단위(모델 노선
 * 이름)는 이 ID로 정해지므로 같은 상태를 쓰는 배치는 항상 같은 줄에서 순서대로 처리되고, 다른 노선은 다른 줄에서 동시에 처리된다.
 * 판본과 열쇠의 짝은 {@link #pickUp()}이 정류장을 읽을 때 기억한다. 신호({@link #batchStored})는 수집 스레드에서 DB를 읽지 않으려고
 * 아직 짝을 모르는 판본의 배치를 넣지 않고 다음 줍기에 맡긴다.
 *
 * <p>줄에서 배치 하나는 이렇게 처리한다.
 * <ol>
 *   <li>옛 경로와 같은 판정으로 모델 문맥을 정한다. 활성 모델이 없거나, 활성 판본이 아니거나, 모델이 노선을 지원하지 않으면 실행
 *       줄 없이 끝낸다(옛 경로도 발행하지 않는다). 확정 판본 통계가 아니면 새 경로가 처리할 수 없어 같은 방식으로 끝낸다.</li>
 *   <li>배치를 줄과 함께 읽는다. 예보 실행 줄이나 옛 경로의 착수 시각이 이미 있으면 처리한 배치라 끝낸다. 줍기와 신호가 같은 배치를
 *       두 번 넣어도 여기서 걸러진다.</li>
 *   <li>착수 시각을 이때 찍는다. 시계 값을 저장 자릿수(마이크로초)로 자른 값이다. 메모리의 노선 상태가 든 시각과 다시 뜬 뒤
 *       DB에서 되감아 읽는 시각이 같아야 하기 때문이다. 이 시각으로 90초 판정을 하고, 실행 줄에 적고, 노선 상태와 되감기의 순서로
 *       쓴다. {@code observation_batch}에는 적지 않는다.</li>
 *   <li>나머지(90초 판정, 되감기, 상태 전진, 예측 조립, 한 트랜잭션 저장, 커밋 뒤 상태 교체와 사건)는 그림자와 같은
 *       {@link PipelineBatchProcessor}가 한다.</li>
 * </ol>
 * 처리 중 예외가 나면 그 노선의 상태를 버리고 실행 줄 없이 끝낸다. 입력 부족(되감기 실패·통과 판정 실패 등)은
 * {@code INPUT_UNAVAILABLE} 실행 줄을 남겨 다시 줍지 않는다.
 *
 * <p>한 노선의 이력에는 배치가 수신 순서대로만 들어가야 한다. 뒤 배치가 먼저 들어가면 앞 배치가 더 늦은 착수 시각으로 뒤에 붙어
 * 연결과 운행이 끊긴 것으로 판정된다. 그래서 예외로 실패한 배치를 이렇게 다룬다.
 * <ul>
 *   <li>같은 줄에서 바로 한 번 다시 처리한다.</li>
 *   <li>그래도 실패하면 그 배치를 메모리에 기억한다({@link HeldBatches}). 그 노선의 다음 배치는 기억한 배치를 먼저 다시 처리하고,
 *       또 실패하면 자기도 처리하지 않고 뒤에서 기다린다.</li>
 *   <li>기억한 배치는 수신 뒤 90초가 지나면 더는 기다리게 하지 않는다. 그 뒤에 처리되면 {@code LATE}로 닫혀 이력에 들어가지
 *       않으므로 순서가 뒤집히지 않는다. 그래서 한 배치가 계속 실패해도 그 노선은 길어야 90초 밀린다.</li>
 * </ul>
 * 기억한 배치와 기다리는 배치는 실행 줄이 없으므로 신선도 창 안에서는 줍기도 다시 넣는다. 기억은 메모리에만 있다. 다시 뜬
 * 직후에는 줍기가 처리하지 않은 배치를 수신 순서로 넣는다. 그래도 신호와 줍기가 엇갈려 뒤 배치가 먼저 이력에 들어갔으면, 앞
 * 배치는 이력에 넣지 않고 실행 줄 없이 둔다({@link PipelineBatchProcessor.Status#OUT_OF_ORDER}). 그 배치는 다음 줍기마다 같은
 * 판정을 받다가 수신 뒤 90초가 지나면 {@code LATE}로 닫힌다.
 *
 * <p>옛 예보 쓰기({@code seat_forecast}, {@code forecast_publication}), {@code input_confirmed_at} 갱신, 체크포인트 파일과
 * {@code alighting_history_checkpoint} 쓰기, {@code route} 행 잠금을 하지 않는다. 옛 발행 저장이 하던 일 가운데 모델 배포의 정산
 * 근거({@code model_deployment.settlement_context}) 적기만은 새 채점이 읽으므로 이어서 한다.
 */
public final class ForecastPipelineService implements ForecastPipeline {
    /** 배치 하나의 처리 시간을 남기는 {@link WorkerOperationLog} 작업 이름. */
    public static final String OPERATION = "pipeline_forecast_batch";
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastPipelineService.class);
    private static final Set<String> SUCCESS = Set.of("SUCCESS_ROWS", "SUCCESS_EMPTY");
    private static final String UNSUPPORTED_PROFILE = "unsupported_profile";
    private static final String FAILED = "failed";
    private static final String RETRIED = "retried";
    private static final String HELD = "held";

    private final ForecastLanes lanes;
    private final PipelineBatchProcessor processor;
    private final ObservationBatchReader batches;
    private final PendingPipelineBatches pending;
    private final ForecastRuntime runtimes;
    private final RouteVersionQuery versions;
    private final RouteStopsQuery stops;
    private final ForecastPolicy policy;
    private final Clock clock;
    private final PipelineForecastMetrics metrics;
    private final Map<Long, String> laneKeys = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final Set<String> contextsStored = ConcurrentHashMap.newKeySet();
    private final Set<String> unsupportedProfilesWarned = ConcurrentHashMap.newKeySet();
    private final Map<String, HeldBatches> heldByKey = new ConcurrentHashMap<>();
    private final SettlementContextWriter settlementContexts;
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;

    /**
     * @param batches 착수 시각을 {@code forecast_run}에서 먼저 읽는 원본({@code RECORDED_RUN} 축)
     * @param settlementContexts 모델 배포에 정산 근거를 적는 곳. 옛 발행 저장이 하던 일이다
     * @param policy 신선도 창과 한 번에 줍는 배치 수. 옛 경로와 같은 값이다
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public ForecastPipelineService(
        ForecastLanes lanes,
        PipelineBatchProcessor processor,
        ObservationBatchReader batches,
        PendingPipelineBatches pending,
        ForecastRuntime runtimes,
        RouteVersionQuery versions,
        RouteStopsQuery stops,
        SettlementContextWriter settlementContexts,
        ForecastPolicy policy,
        Clock clock,
        MeterRegistry meters
    ) {
        this.lanes = Objects.requireNonNull(lanes);
        this.processor = Objects.requireNonNull(processor);
        this.batches = Objects.requireNonNull(batches);
        this.pending = Objects.requireNonNull(pending);
        this.runtimes = Objects.requireNonNull(runtimes);
        this.versions = Objects.requireNonNull(versions);
        this.stops = Objects.requireNonNull(stops);
        this.settlementContexts = Objects.requireNonNull(settlementContexts);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = new PipelineForecastMetrics(meters, processor.states());
    }

    /** 줍기에서 찾은 가장 오래된 처리하지 않은 배치와 커밋한 예보 실행을 알릴 진단 수신자를 정한다. */
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    /** 들고 있는 노선 상태의 등록부. */
    public RouteStateRegistry states() {
        return processor.states();
    }

    @Override
    public void batchStored(final long observationBatchId, final long routeVersionId) {
        String key = laneKeys.get(routeVersionId);
        if (key == null) {
            metrics.unrouted();
            return;
        }
        enqueue(new Queued(observationBatchId, routeVersionId, key, clock.instant()));
    }

    @Override
    public void pickUp() {
        try {
            WorkerOperationLog.run("pipeline_forecast_pickup", "all", this::pickUpOnce);
        } catch (RuntimeException alreadyLogged) {
            LOGGER.warn("event=pipeline_forecast_pickup_failed exceptionType={}",
                alreadyLogged.getClass().getSimpleName());
        }
    }

    private void pickUpOnce() {
        Optional<RuntimeSnapshot> runtime = runtimes.resolveActive();
        if (runtime.isEmpty()) {
            return;
        }
        Instant notBefore = clock.instant().minus(policy.staleness());
        for (final long routeVersionId : versions.findActiveVersionIds()) {
            try {
                RouteStops routeStops = stops.readStops(routeVersionId);
                laneKeys.put(routeVersionId, routeStops.sourceRouteId());
                if (resolve(runtime.get(), routeStops).context() == null) {
                    continue;
                }
                List<PendingPipelineBatches.Pending> found =
                    pending.find(routeVersionId, notBefore, policy.batchLimit());
                reportPending(routeVersionId, found);
                for (PendingPipelineBatches.Pending batch : found) {
                    enqueue(new Queued(batch.observationBatchId(), routeVersionId, routeStops.sourceRouteId(),
                        batch.receivedAt()));
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("event=pipeline_forecast_pickup_route_failed routeVersionId={} exceptionType={}",
                    routeVersionId, failure.getClass().getSimpleName(), failure);
            }
        }
    }

    private void reportPending(final long routeVersionId, List<PendingPipelineBatches.Pending> found) {
        Instant oldest = found.stream()
            .map(PendingPipelineBatches.Pending::receivedAt)
            .min(Instant::compareTo)
            .orElse(null);
        try {
            telemetry.pending(routeVersionId, oldest);
        } catch (RuntimeException ignored) {
            LOGGER.debug("event=pipeline_forecast_telemetry_failed routeVersionId={}", routeVersionId);
        }
    }

    private void enqueue(Queued batch) {
        if (!queued.add(batch.observationBatchId())) {
            return;
        }
        final boolean accepted;
        try {
            accepted = lanes.submit(batch.key(), () -> run(batch));
        } catch (RuntimeException failure) {
            queued.remove(batch.observationBatchId());
            metrics.rejected();
            return;
        }
        if (!accepted) {
            queued.remove(batch.observationBatchId());
            metrics.rejected();
        }
    }

    /**
     * 줄에서 꺼낸 배치를 처리한다. 노선별 실행 줄의 스레드에서만 부른다. 예외를 던지지 않는다.
     * 앞서 실패해 기다리는 배치가 있으면 그것부터 수신 순서로 다시 처리하고, 아직 이력에 들어갈 수 있는 배치가 또 실패하면 이 배치도
     * 처리하지 않고 뒤에서 기다리게 한다. 이 배치가 이미 이력에 들어갈 수 없으면 기다리지 않고 바로 처리한다.
     * 줍기가 기다리는 배치를 한꺼번에 다시 넣어도 맨 앞 배치만 다시 처리한다. 뒤에서 기다리는 배치는 맨 앞이 풀릴 때 함께 처리된다.
     */
    private void run(Queued batch) {
        queued.remove(batch.observationBatchId());
        HeldBatches held = heldByKey.computeIfAbsent(batch.key(), key -> new HeldBatches());
        if (held.waitsBehindOther(batch.observationBatchId()) && canStillEnterHistory(batch)) {
            return;
        }
        final boolean wasHeld = held.contains(batch.observationBatchId());
        final boolean blocked = retryHeld(held);
        if (wasHeld) {
            return;
        }
        if (blocked && canStillEnterHistory(batch)) {
            metrics.batch(HELD);
            hold(held, batch);
            return;
        }
        processWithRetry(held, batch);
    }

    /**
     * 기다리는 배치를 수신 순서로 다시 처리한다. 아직 이력에 들어갈 수 있는 배치가 또 실패하면 거기서 멈추고 참을 돌려준다.
     * 수신 뒤 90초가 지난 배치는 다시 처리해도 이력에 들어가지 않으므로 실패해도 뒤 배치를 막지 않는다.
     */
    private boolean retryHeld(HeldBatches held) {
        while (!held.isEmpty()) {
            HeldBatches.Held head = held.head();
            String result = process(head.observationBatchId(), head.routeVersionId());
            metrics.batch(result);
            if (FAILED.equals(result) && !startedAt().isAfter(head.releaseAt())) {
                return true;
            }
            held.removeHead();
        }
        return false;
    }

    /** 예외로 실패하면 같은 줄에서 바로 한 번 다시 처리하고, 그래도 실패하면 뒤 배치가 앞지르지 않게 기억해 둔다. */
    private void processWithRetry(HeldBatches held, Queued batch) {
        String result = process(batch.observationBatchId(), batch.routeVersionId());
        if (!FAILED.equals(result)) {
            metrics.batch(result);
            return;
        }
        metrics.batch(RETRIED);
        String retried = process(batch.observationBatchId(), batch.routeVersionId());
        metrics.batch(retried);
        if (FAILED.equals(retried) && canStillEnterHistory(batch)) {
            hold(held, batch);
        }
    }

    /** 지금 착수해도 수신 뒤 90초 안일 수 있는지다. 거짓이면 처리해도 늦음으로 닫혀 이력에 들어가지 않는다. */
    private boolean canStillEnterHistory(Queued batch) {
        return !startedAt().isAfter(batch.releaseAt());
    }

    private void hold(HeldBatches held, Queued batch) {
        held.add(new HeldBatches.Held(batch.observationBatchId(), batch.routeVersionId(), batch.releaseAt()));
        LOGGER.warn("event=pipeline_forecast_held routeKey={} batchId={} held={}", batch.key(),
            batch.observationBatchId(), held.size());
    }

    /** 처리를 시작하는 시각이다. 저장 자릿수(마이크로초)로 자른다. */
    private Instant startedAt() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 배치 하나를 한 번 처리한다. 노선별 실행 줄의 스레드에서만 부른다. 예외를 던지지 않는다.
     *
     * @param observationBatchId 관측 배치
     * @param routeVersionId 배치의 노선 판본
     * @return 이 처리의 결과 분류({@code salmonbus.pipeline.forecast.batches}의 태그)
     */
    private String process(final long observationBatchId, final long routeVersionId) {
        return WorkerOperationLog.measure(OPERATION, routeVersionId, () -> {
            final long started = System.nanoTime();
            String result = classify(observationBatchId, routeVersionId);
            metrics.duration(Duration.ofNanos(System.nanoTime() - started));
            return result;
        });
    }

    /** 처리 중 예외가 나면 그 노선의 상태를 버리고 {@code failed}로 분류한다. */
    private String classify(final long observationBatchId, final long routeVersionId) {
        String route = null;
        try {
            Resolved resolved = resolve(routeVersionId);
            if (resolved.context() == null) {
                return resolved.skipped();
            }
            RouteContext context = resolved.context();
            route = context.route();
            return process(context, observationBatchId);
        } catch (RuntimeException failure) {
            if (route != null) {
                processor.states().remove(route);
            }
            LOGGER.warn("event=pipeline_forecast_failed route={} batchId={} exceptionType={}", route,
                observationBatchId, failure.getClass().getSimpleName(), failure);
            return FAILED;
        }
    }

    private String process(RouteContext context, final long observationBatchId) {
        Optional<ObservationBatchRead> found = batches.readBatch(observationBatchId);
        if (found.isEmpty()) {
            return "missing";
        }
        ObservationBatchRead read = found.get();
        if (read.batch().confirmedAt() != null) {
            return "already_processed";
        }
        if (!SUCCESS.contains(read.batch().outcome())) {
            return "not_success";
        }
        storeSettlementContext(context);
        Instant confirmedAt = startedAt();
        if (read.batch().receivedAt() != null) {
            metrics.lag(Duration.between(read.batch().receivedAt(), confirmedAt));
        }
        PipelineBatchProcessor.Processed processed = processor.process(context, read.withConfirmedAt(confirmedAt));
        reportPublished(processed);
        if (processed.replayed() != null) {
            metrics.replay(processed.replayed());
        }
        metrics.replayExtension(processed.replayExtension());
        processed.exclusions().values().forEach(metrics::excluded);
        metrics.unattributedPasses(processed.unattributedPasses());
        metrics.recoveredPasses(processed.recoveredPasses());
        return switch (processed.status()) {
            case STALE -> "stale";
            case DUPLICATE -> "duplicate";
            case OUT_OF_ORDER -> "out_of_order";
            case SAVED -> savedResult(processed);
        };
    }

    /**
     * 예보 실행을 커밋한 배치를 진단 수신자에 알린다. 예측이 없는 실행(늦음·빈 응답·범위 밖)도 커밋으로 알린다. 입력 부족으로 끝난
     * 실행은 예보를 내지 못한 것이라 알리지 않고, 같은 배치의 실행 줄이 이미 있어 저장하지 않은 처리도 알리지 않는다.
     */
    private void reportPublished(PipelineBatchProcessor.Processed processed) {
        if (processed.status() != PipelineBatchProcessor.Status.SAVED) {
            return;
        }
        ForecastPublished published = processed.published();
        if (published.outcome() == ForecastOutcome.INPUT_UNAVAILABLE) {
            return;
        }
        try {
            telemetry.published(published.routeId(), published.routeVersionId(), published.receivedAt(),
                published.predictionCount());
        } catch (RuntimeException ignored) {
            LOGGER.debug("event=pipeline_forecast_telemetry_failed routeVersionId={}", published.routeVersionId());
        }
    }

    private static String savedResult(PipelineBatchProcessor.Processed processed) {
        if (processed.special() != null) {
            return processed.special();
        }
        return processed.published().outcome().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 모델 배포에 이 노선 판본의 정산 근거를 (배포, 판본)마다 한 번 적는다. 새 채점이 읽는다. 옛 경로에서는 옛 발행 저장이 예측이 있는
     * 첫 발행에서 적었다. 근거는 모델과 통계만으로 정해지므로 같은 값이다. 모델에 이 노선의 근거가 없으면 적지 않는다.
     */
    private void storeSettlementContext(RouteContext context) {
        String key = context.runtime().deploymentId() + ":" + context.stops().routeVersionId();
        if (contextsStored.contains(key)
            || !SettlementContextDocument.available(context.runtime().model(), context.statistics())) {
            return;
        }
        settlementContexts.store(context.runtime().deploymentId(), context.stops().routeVersionId(),
            SettlementContextDocument.of(context.runtime().model(), context.statistics()));
        contextsStored.add(key);
    }

    /** 옛 경로의 노선 진입({@code writeForecastsOfRoute})과 같은 판정이다. */
    private Resolved resolve(final long routeVersionId) {
        Optional<RuntimeSnapshot> runtime = runtimes.resolveActive();
        if (runtime.isEmpty()) {
            return Resolved.skip("no_model");
        }
        if (!versions.findActiveVersionIds().contains(routeVersionId)) {
            return Resolved.skip("inactive_version");
        }
        RouteStops routeStops = stops.readStops(routeVersionId);
        laneKeys.putIfAbsent(routeVersionId, routeStops.sourceRouteId());
        Resolved resolved = resolve(runtime.get(), routeStops);
        warnUnsupportedProfileOnce(runtime.get().deploymentId(), routeVersionId, resolved);
        return resolved;
    }

    /**
     * 확정 프로필이 아닌 모델의 노선은 새 경로가 예보하지 못한다. 실행 줄도 남지 않아 보드는 발행이 끊긴 것으로 본다.
     * (모델 배포, 노선 판본)마다 한 번만 경고한다.
     */
    private void warnUnsupportedProfileOnce(
        final long modelDeploymentId,
        final long routeVersionId,
        Resolved resolved
    ) {
        if (!UNSUPPORTED_PROFILE.equals(resolved.skipped())) {
            return;
        }
        if (unsupportedProfilesWarned.add(modelDeploymentId + ":" + routeVersionId)) {
            LOGGER.warn("event=pipeline_forecast_unsupported_profile routeVersionId={} modelDeploymentId={}",
                routeVersionId, modelDeploymentId);
        }
    }

    private static Resolved resolve(RuntimeSnapshot runtime, RouteStops routeStops) {
        if (!runtime.covers(routeStops.sourceRouteId())) {
            return Resolved.skip("not_covered");
        }
        if (!runtime.model().supportsRoute(routeStops)) {
            return Resolved.skip("reference_mismatch");
        }
        AlightingAwareRouteStatistics statistics = runtime.model().statistics(routeStops);
        if (!statistics.confirmedProfile()) {
            return Resolved.skip(UNSUPPORTED_PROFILE);
        }
        return new Resolved(new RouteContext(statistics, routeStops, runtime), null);
    }

    /** 모델 문맥 또는 처리하지 않는 사유다. */
    private record Resolved(RouteContext context, String skipped) {
        static Resolved skip(String reason) {
            return new Resolved(null, reason);
        }
    }

    /**
     * 줄에 넣은 배치 하나다.
     *
     * @param key 노선 열쇠
     * @param receivedNoLaterThan 배치의 수신 시각보다 이르지 않은 시각. 줍기는 수신 시각을, 신호는 신호를 받은 시각을 넣는다.
     *     이 배치를 언제까지 기다리게 할지 정하는 데 쓴다
     */
    private record Queued(long observationBatchId, long routeVersionId, String key, Instant receivedNoLaterThan) {
        /** 이 시각이 지나 착수하면 수신 뒤 90초를 넘긴 것이 확실하다. */
        private Instant releaseAt() {
            return receivedNoLaterThan.plus(ForecastBatch.MAX_CONFIRMATION_DELAY);
        }
    }
}
