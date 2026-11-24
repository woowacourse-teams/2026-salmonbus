package com.gustler.backend.forecasting.application.forecast;

import static com.gustler.backend.diagnostics.Logfmt.quote;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
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
 * 수집이 커밋한 배치를 노선별로 조회해 예보한다.
 *
 * <p>수집 알림({@link #batchStored})과 주기 조회({@link #pickUp()})는 노선별 실행기({@link ForecastLanes})에
 * 미처리 배치 조회 작업을 등록한다. 작업은 DB에서 배치를 수신 시각과 배치 번호 순으로 읽고 차례로 처리한다.
 * 실행기 배정 키는 GBIS 노선 ID다. 같은 노선 상태를 사용하는 배치는 같은 단일 스레드에서 처리하고,
 * 다른 노선은 병렬로 처리할 수 있다. 노선 버전과 배정 키의 관계는 정류장을 조회할 때 보관한다.
 * 키를 모르는 버전의 수집 알림은 작업을 등록하지 않고 다음 주기 조회에 맡겨 수집 스레드에서 DB를 읽지 않는다.
 *
 * <p>처리 순서가 바뀌면 늦게 반영한 관측을 새 연결이나 새 운행으로 잘못 판정할 수 있다. 다음 규칙으로 순서를 지킨다.
 * <ul>
 *   <li>같은 노선 버전의 조회 작업이 대기 중이면 중복 등록하지 않는다. 작업 시작 시 대기 표시를 지운 뒤 배치를 읽는다.
 *       처리 중 커밋한 배치는 현재 조회 또는 새로 등록한 다음 조회에서 처리한다.</li>
 *   <li>예외가 나면 조회 작업을 중단해 뒤 배치가 먼저 반영되지 않게 한다. 실행 기록이 없는 실패 배치는 다음 조회에서 다시 읽는다.</li>
 *   <li>실패한 배치가 수신 후 90초를 넘기면 건너뛰고 다음 배치를 처리한다. 이후 처리해도 {@code LATE}로 종료하며
 *       이력에는 반영하지 않는다. 한 배치의 반복 실패로 같은 노선이 지연되는 시간은 최대 90초다.</li>
 *   <li>조회 한도까지 읽었고 처리를 마친 배치가 있다면 조회 작업을 한 번 더 등록한다.
 *       완료한 배치가 없으면 같은 배치만 다시 읽게 되므로 다음 알림이나 주기 조회를 기다린다.</li>
 * </ul>
 * 알림을 받지 못했거나 프로세스 중단으로 남은 배치는 유효 시간 범위 안에서 주기 조회가 찾아 처리한다.
 *
 * <p>활성 모델이 없거나 현재 노선 버전이 아니거나 모델이 지원하지 않는 노선이면 배치를 조회하지 않는다.
 * 확정 입력 프로필의 통계가 없는 경우도 같다. 처리할 모델을 선택한 뒤 배치마다 다음 순서로 진행한다.
 * <ol>
 *   <li>배치와 저장된 관측을 읽는다. 예보 실행 기록에 처리 시작 시각이 있으면 이미 처리한 배치이므로 종료한다.</li>
 *   <li>현재 시각을 마이크로초 단위로 잘라 처리 시작 시각을 정한다. 메모리 상태와 재시작 후 DB에서 복구한 상태가
 *       같은 시각을 사용해야 한다. 이 값으로 90초 조건을 검사하고 처리 순서를 정하며 예보 실행 기록에도 저장한다.
 *       {@code observation_batch}에는 기록하지 않는다.</li>
 *   <li>90초 조건 검사, 노선 상태 복구와 갱신, 예측 생성, 트랜잭션 저장, 커밋 후 상태 교체와 이벤트 전달은
 *       {@link ForecastBatchProcessor}가 처리한다.</li>
 * </ol>
 * 처리 중 예외가 나면 노선 상태를 제거하고 실행 기록을 남기지 않는다.
 * 복구나 통과 판정에 필요한 입력이 부족하면 {@code INPUT_UNAVAILABLE} 기록을 남겨 다시 조회하지 않는다.
 *
 * <p>실행기가 종료 중이면({@link ForecastLanes#closing()}) 현재 배치 하나만 마친다.
 * 배치를 읽었지만 아직 처리하지 않았다면 첫 배치 하나만 처리한다. 남은 배치는 재시작 후 조회한다.
 *
 * <p>진단 로그는 세 종류다. 정원을 모르거나 모델 범위를 벗어나 제외한 차량은 배치마다
 * {@code event=forecast_vehicle_withheld}로 남긴다. 모델 배포 변경과 기준 정류장 불일치는
 * {@link ForecastModelUsageLog}가 상태 변경 시 기록한다. 활성 모델이 없어지거나 다시 생기면 주기 조회가 한 번씩 기록한다.
 *
 * <p>{@code observation_batch}를 변경하거나 {@code route} 행을 잠그지 않는다.
 * 정산에서 사용할 {@code model_deployment.settlement_context}는 배치 처리 전에 기록한다.
 */
public final class RunForecastsService implements RunForecasts {

    /** 배치 하나의 처리 시간을 남기는 {@link WorkerOperationLog} 작업 이름. */
    public static final String OPERATION = "forecast_batch";
    private static final Logger LOGGER = LoggerFactory.getLogger(RunForecastsService.class);
    private static final Set<String> SUCCESS = Set.of("SUCCESS_ROWS", "SUCCESS_EMPTY");
    private static final String UNSUPPORTED_PROFILE = "unsupported_profile";
    private static final String FAILED = "failed";
    /** 실행 기록을 남기지 못한 처리의 결과 분류다. 그런 배치는 처리하지 않은 배치 조회에 다시 나온다. */
    private static final Set<String> LEFT_UNPROCESSED = Set.of(FAILED, "stale", "out_of_order");

    private final ForecastLanes lanes;
    private final ForecastBatchProcessor processor;
    private final ObservationBatchReader batches;
    private final PendingBatches pending;
    private final ForecastRuntime runtimes;
    private final RouteVersionQuery versions;
    private final RouteStopsQuery stops;
    private final ForecastPolicy policy;
    private final Clock clock;
    private final ForecastMetrics metrics;
    private final Map<Long, String> laneKeys = new ConcurrentHashMap<>();
    private final Set<Long> sweepsQueued = ConcurrentHashMap.newKeySet();
    private final Set<String> contextsStored = ConcurrentHashMap.newKeySet();
    private final Set<String> unsupportedProfilesWarned = ConcurrentHashMap.newKeySet();
    private final ForecastModelUsageLog modelUsage = new ForecastModelUsageLog();
    private final SettlementContextWriter settlementContexts;
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;
    private volatile boolean modelMissing;

    /**
     * @param batches 배치를 처리 시작 시각과 같이 읽는 원본. 복구와 같은 원본이어야 한다
     * @param settlementContexts 모델 배포에 정산 근거를 적는 곳
     * @param policy 유효 시간 범위와 한 번에 읽는 배치 수
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public RunForecastsService(
        ForecastLanes lanes,
        ForecastBatchProcessor processor,
        ObservationBatchReader batches,
        PendingBatches pending,
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
        this.metrics = new ForecastMetrics(meters, processor.states());
    }

    /** 미처리 배치 중 가장 오래된 배치와 커밋한 예보 실행을 전달받을 진단 객체를 설정한다. */
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    /** 보관 중인 노선 상태의 등록부. */
    public RouteStateRegistry states() {
        return processor.states();
    }

    /** 커밋 후 호출되므로 작업 등록 오류를 수집 저장의 실패로 전파하지 않는다. */
    @Override
    public void batchStored(final long observationBatchId, final long routeVersionId) {
        String laneKey = laneKeys.get(routeVersionId);
        if (laneKey == null) {
            metrics.unrouted();
            return;
        }
        try {
            requestSweep(laneKey, routeVersionId);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=forecast_signal_failed routeVersionId={} exceptionType={}",
                routeVersionId, failure.getClass().getSimpleName());
        }
    }

    @Override
    public void pickUp() {
        try {
            WorkerOperationLog.run("forecast_pickup", "all", this::pickUpOnce);
        } catch (RuntimeException alreadyLogged) {
            LOGGER.warn("event=forecast_pickup_failed exceptionType={}", alreadyLogged.getClass().getSimpleName());
        }
    }

    private void pickUpOnce() {
        Optional<RuntimeSnapshot> runtime = runtimes.resolveActive();
        reportModelAvailability(runtime);
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
                Optional<PendingBatches.Pending> oldest = pending.find(routeVersionId, notBefore, 1)
                    .stream()
                    .findFirst();
                reportPending(routeVersionId, oldest);
                if (oldest.isPresent()) {
                    requestSweep(routeStops.sourceRouteId(), routeVersionId);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn(
                        "event=forecast_pickup_route_failed routeVersionId={} exceptionType={}",
                        routeVersionId,
                        failure.getClass().getSimpleName()
                );
            }
        }
    }

    /**
     * 활성 모델이 없어진 때와 다시 생긴 때를 한 번씩 남긴다. 모델이 없는 동안에는 배치를 읽지 않고 실행 기록도 남기지 않는다.
     */
    private void reportModelAvailability(Optional<RuntimeSnapshot> runtime) {
        if (runtime.isEmpty() == modelMissing) {
            return;
        }
        modelMissing = runtime.isEmpty();
        if (modelMissing) {
            LOGGER.warn("event=forecast_model_unavailable");
            return;
        }
        LOGGER.info("event=forecast_model_available modelDeploymentId={} releaseId={}", runtime.get().deploymentId(),
            quote(runtime.get().releaseId()));
    }

    private void reportPending(final long routeVersionId, Optional<PendingBatches.Pending> oldest) {
        Instant oldestReceivedAt = oldest.map(PendingBatches.Pending::receivedAt).orElse(null);
        try {
            telemetry.pending(routeVersionId, oldestReceivedAt);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=forecast_telemetry_failed routeVersionId={} exceptionType={}",
                routeVersionId, failure.getClass().getSimpleName());
        }
    }

    /** 노선 버전의 미처리 배치 조회 작업을 실행기에 등록한다. 이미 대기 중이면 중복 등록하지 않는다. */
    private void requestSweep(String laneKey, final long routeVersionId) {
        if (!sweepsQueued.add(routeVersionId)) {
            return;
        }
        boolean accepted = false;
        try {
            accepted = lanes.submit(laneKey, () -> sweep(laneKey, routeVersionId));
        } finally {
            if (!accepted) {
                sweepsQueued.remove(routeVersionId);
            }
        }
        if (!accepted) {
            metrics.rejected();
        }
    }

    /**
     * 큐에서 꺼낸 배치 조회 작업을 실행한다. 노선별 실행기의 스레드에서만 호출한다. 예외를 던지지 않는다.
     * 기다림 표시를 배치를 읽기 전에 내린다. 그래야 읽은 뒤에 커밋된 배치의 신호가 다음 배치 조회 작업을 넣는다.
     */
    private void sweep(String laneKey, final long routeVersionId) {
        sweepsQueued.remove(routeVersionId);
        try {
            final boolean moreBatchesReady = sweepOnce(routeVersionId);
            if (moreBatchesReady) {
                requestSweep(laneKey, routeVersionId);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=forecast_sweep_failed routeVersionId={} exceptionType={}",
                    routeVersionId,
                    failure.getClass().getSimpleName()
            );
        }
    }

    /**
     * 노선 버전의 처리하지 않은 배치를 수신 순서로 한 번 읽어 차례로 처리한다.
     *
     * @return 한도만큼 읽었고 그 가운데 처리를 마쳐 다음 조회에서 빠지는 배치가 있어, 남은 배치를 바로 이어서 훑어야 하면 참
     */
    private boolean sweepOnce(final long routeVersionId) {
        Resolved resolved = resolve(routeVersionId);
        if (resolved.context() == null) {
            metrics.batch(resolved.skipped());
            return false;
        }
        Instant notBefore = clock.instant().minus(policy.staleness());
        List<PendingBatches.Pending> pendingBatches = pending.find(routeVersionId, notBefore, policy.batchLimit());
        boolean anyBatchFinished = false;
        for (int index = 0; index < pendingBatches.size(); index++) {
            if (index > 0 && lanes.closing()) {
                return false;
            }
            PendingBatches.Pending batch = pendingBatches.get(index);
            String result = measure(resolved.context(), batch.observationBatchId());
            metrics.batch(result);
            if (!LEFT_UNPROCESSED.contains(result)) {
                anyBatchFinished = true;
                continue;
            }
            if (FAILED.equals(result) && canStillEnterHistory(batch)) {
                LOGGER.warn(
                        "event=forecast_sweep_blocked routeVersionId={} batchId={} waiting={}",
                        routeVersionId,
                        batch.observationBatchId(),
                        pendingBatches.size() - index - 1
                );
                return false;
            }
        }
        return anyBatchFinished && pendingBatches.size() >= policy.batchLimit();
    }

    /** 지금 처리를 시작하면 수신 후 90초 안인지 확인한다. 시간이 지났으면 이력에 반영하지 않고 LATE로 종료한다. */
    private boolean canStillEnterHistory(PendingBatches.Pending batch) {
        return !startedAt().isAfter(batch.receivedAt().plus(ForecastBatch.MAX_CONFIRMATION_DELAY));
    }

    /** 처리를 시작하는 시각이다. 저장 자릿수(마이크로초)로 자른다. */
    private Instant startedAt() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 배치 하나를 한 번 처리하고 걸린 시간을 남긴다. 노선별 실행기의 스레드에서만 호출한다. 예외를 던지지 않는다.
     *
     * @return 이 처리의 결과 분류({@code salmonbus.forecast.batches}의 태그)
     */
    private String measure(RouteContext context, final long observationBatchId) {
        return WorkerOperationLog.measure(OPERATION, context.stops().routeVersionId(), () -> {
            final long started = System.nanoTime();
            String result = classify(context, observationBatchId);
            metrics.duration(Duration.ofNanos(System.nanoTime() - started));
            return result;
        });
    }

    /** 처리 중 예외가 나면 그 노선의 상태를 버리고 {@code failed}로 분류한다. */
    private String classify(RouteContext context, final long observationBatchId) {
        try {
            return process(context, observationBatchId);
        } catch (RuntimeException failure) {
            processor.states().remove(context.route());
            LOGGER.warn(
                    "event=forecast_failed route={} batchId={} exceptionType={}",
                    context.route(),
                    observationBatchId,
                    failure.getClass().getSimpleName()
            );
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
        ForecastBatchProcessor.Processed processed = processor.process(context, read.withConfirmedAt(confirmedAt));
        reportBatchResult(context, observationBatchId, processed);
        return switch (processed.status()) {
            case STALE -> "stale";
            case DUPLICATE -> "duplicate";
            case OUT_OF_ORDER -> "out_of_order";
            case SAVED -> savedResult(processed);
        };
    }

    private void reportBatchResult(
        RouteContext context,
        final long observationBatchId,
        ForecastBatchProcessor.Processed processed
    ) {
        reportPublished(processed);
        if (processed.replayed() != null) {
            metrics.replay(processed.replayed());
        }
        processed.exclusions().values().forEach(metrics::excluded);
        processed.withheld().forEach(vehicle -> reportWithheld(observationBatchId, vehicle));
        reportModelUsed(context, processed);
        metrics.unattributedPasses(processed.unattributedPasses());
        metrics.recoveredPasses(processed.recoveredPasses());
    }

    /**
     * 예보 실행을 커밋한 배치를 진단 수신자에 알린다. 예측이 없는 실행(늦음, 빈 응답, 범위 밖)도 커밋으로 알린다. 입력 부족으로 끝난
     * 실행은 예보를 내지 못한 것이라 알리지 않고, 같은 배치의 실행 기록이 이미 있어 저장하지 않은 처리도 알리지 않는다.
     */
    private void reportPublished(ForecastBatchProcessor.Processed processed) {
        if (processed.status() != ForecastBatchProcessor.Status.SAVED) {
            return;
        }
        ForecastPublished published = processed.published();
        if (published.outcome() == ForecastOutcome.INPUT_UNAVAILABLE) {
            return;
        }
        try {
            telemetry.published(
                    published.routeId(),
                    published.routeVersionId(),
                    published.receivedAt(),
                    published.predictionCount()
            );
        } catch (RuntimeException failure) {
            LOGGER.warn("event=forecast_telemetry_failed routeVersionId={} exceptionType={}",
                published.routeVersionId(), failure.getClass().getSimpleName());
        }
    }

    /** 정원 때문에 예보에서 뺀 차량을 배치마다 남긴다. 같은 차량이 다음 배치에서도 빠지면 다시 남긴다. */
    private void reportWithheld(final long observationBatchId, ForecastStageResult.WithheldVehicle vehicle) {
        metrics.excluded(vehicle.reason());
        LOGGER.info(
                "event=forecast_vehicle_withheld reason={} batchId={} vehicleId={}",
                vehicle.reason(),
                observationBatchId,
                vehicle.vehicleId()
        );
    }

    /** 예측을 한 건 이상 저장한 배치의 모델 배포를 알린다. 노선의 배포가 바뀔 때만 로그가 남는다. */
    private void reportModelUsed(RouteContext context, ForecastBatchProcessor.Processed processed) {
        if (processed.status() != ForecastBatchProcessor.Status.SAVED) {
            return;
        }
        ForecastPublished published = processed.published();
        if (published.predictionCount() == 0) {
            return;
        }
        modelUsage.committed(
                context.stops().sourceRouteId(),
                context.stops().routeName(),
                published.routeVersionId(),
                published.observationBatchId(),
                context.runtime()
        );
    }

    private static String savedResult(ForecastBatchProcessor.Processed processed) {
        if (processed.special() != null) {
            return processed.special();
        }
        return processed.published().outcome().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 모델 배포에 이 노선 버전의 정산 근거를 (배포, 버전)마다 한 번 기록한다. 정산이 읽는다. 근거는 모델과 통계만으로 정해진다.
     * 모델에 이 노선의 근거가 없으면 적지 않는다.
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

    /** 활성 모델, 활성 버전, 모델의 노선 지원 여부로 모델 정보를 정한다. */
    private Resolved resolve(final long routeVersionId) {
        Optional<RuntimeSnapshot> runtime = runtimes.resolveActive();
        if (runtime.isEmpty()) {
            return Resolved.skip("no_model");
        }
        if (!versions.findActiveVersionIds().contains(routeVersionId)) {
            return Resolved.skip("inactive_version");
        }
        RouteStops routeStops = stops.readStops(routeVersionId);
        Resolved resolved = resolve(runtime.get(), routeStops);
        warnUnsupportedProfileOnce(runtime.get().deploymentId(), routeVersionId, resolved);
        return resolved;
    }

    /**
     * 확정 프로필이 아닌 모델의 노선은 예보하지 못한다. 실행 기록도 남지 않아 보드는 예보가 끊긴 것으로 본다.
     * (모델 배포, 노선 버전)마다 한 번만 경고한다.
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
            LOGGER.warn("event=forecast_unsupported_profile routeVersionId={} modelDeploymentId={}",
                routeVersionId, modelDeploymentId);
        }
    }

    private Resolved resolve(RuntimeSnapshot runtime, RouteStops routeStops) {
        if (!runtime.covers(routeStops.sourceRouteId())) {
            return Resolved.skip("not_covered");
        }
        if (!runtime.model().supportsRoute(routeStops)) {
            modelUsage.referenceMismatch(
                    routeStops.sourceRouteId(),
                    routeStops.routeName(),
                    routeStops.routeVersionId(),
                    runtime
            );
            return Resolved.skip("reference_mismatch");
        }
        AlightingAwareRouteStatistics statistics = runtime.model().statistics(routeStops);
        if (!statistics.confirmedProfile()) {
            return Resolved.skip(UNSUPPORTED_PROFILE);
        }
        return new Resolved(new RouteContext(statistics, routeStops, runtime), null);
    }

    /** 선택한 모델 정보 또는 예보를 실행하지 않는 사유다. */
    private record Resolved(RouteContext context, String skipped) {

        static Resolved skip(String reason) {
            return new Resolved(null, reason);
        }
    }
}
