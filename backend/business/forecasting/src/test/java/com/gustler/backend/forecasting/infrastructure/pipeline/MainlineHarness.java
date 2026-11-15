package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ReplaySource;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.configuration.ForecastAssembly;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.support.CommittedBatches;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 흐름 하나와 예보 서비스를 묶는 시험 도우미다. 노선별 실행 줄은 넣는 즉시 같은 스레드에서 실행해 배치 순서를 시험이 정한다.
 * 착수 시각은 시계에서 찍으므로 신호마다 시계를 정해 넣는다.
 *
 * <p>흐름의 배치는 미리 모두 DB에 있다. 예보는 수집이 커밋한 것으로 친 배치만 본다({@link CommittedBatches}). 신호는 그 배치를
 * 커밋한 것으로 치고 알리므로, 앞선 배치가 모두 처리돼 있으면 신호 하나가 그 배치 하나를 그 시각에 처리한다.
 *
 * <p>예보 실행 저장과 되감기 읽기에 실패를 끼워 넣을 수 있다. 저장 실패는 아무것도 쓰기 전에 던진다. 시험 트랜잭션 하나에 모든
 * 쓰기가 합류하므로 실패한 저장이 되돌려지지 않기 때문이다.
 */
final class MainlineHarness {
    static final Instant EVENING = Instant.parse("2026-10-14T09:30:00Z");
    static final String REPLAY_FAILURE = "TEST_REPLAY_FAILURE";
    private static final String SOURCE_ROUTE_ID = "234001739";
    private static final String MODEL_ROUTE = "6801";

    final ObservationStreamFixture.Stream stream;
    final PipelineTestSupport.MutableClock clock = new PipelineTestSupport.MutableClock();
    final RuntimeSnapshot runtime;
    final JdbcReplaySource source;
    final ForecastStage stage;
    private final JdbcClient jdbc;
    private final Beans beans;
    private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
    private final Map<Long, Meta> meta = new HashMap<>();
    private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
    private final Map<Long, Integer> saveFailures = new HashMap<>();
    private final Deque<Runnable> waitingTasks = new ArrayDeque<>();
    private final CommittedBatches committed;
    private final VehicleCapacityCache capacities;
    private final AlightingAwareSeatForecastModel model;
    private SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ForecastPipelineService mainline;
    private int replayFailures;
    private int batchLimit = 20;
    private boolean laneHeld;
    private boolean modelActive = true;

    /**
     * @param beans 시험이 주입받은 저장소와 트랜잭션
     * @param seed 흐름과 모델의 씨앗
     * @param length 흐름의 길이. 배치는 30초 간격이다
     */
    MainlineHarness(Beans beans, final long seed, Duration length) {
        this.beans = beans;
        this.jdbc = beans.jdbc();
        stream = ObservationStreamFixture.insert(jdbc, EVENING, length, Duration.ofSeconds(30), null, seed,
            MODEL_ROUTE);
        jdbc.sql("""
            SELECT id,input_confirmed_at,route_version_id,response_received_at,ingestion_origin,outcome
            FROM observation_batch WHERE route_version_id IN (:versions)
            """).param("versions", versions())
            .query(row -> {
                intendedConfirmation.put(row.getLong(1), row.getObject(2, OffsetDateTime.class).toInstant());
                meta.put(row.getLong(1), new Meta(row.getLong(3),
                    row.getObject(4, OffsetDateTime.class).toInstant(), row.getString(5), row.getString(6)));
            });
        jdbc.sql("UPDATE observation_batch SET input_confirmed_at=NULL WHERE route_version_id IN (:versions)")
            .param("versions", versions()).update();
        stream.stopsByVersion().forEach((version, stops) -> stopsByVersion.put(version,
            new RouteStops(version, SOURCE_ROUTE_ID, stops.stops(), stops.routeName())));
        model = model(new Random(seed));
        runtime = activate(model);
        source = new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS);
        capacities = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
        stage = new ForecastStage(new CachedVehicleCapacities(capacities));
        committed = new CommittedBatches(new JdbcPendingPipelineBatches(jdbc));
    }

    /**
     * 예보 서비스를 새로 띄운다. 메모리의 노선 상태·줄에서 기다리던 일·지표가 모두 빈 채로 시작하므로 다시 뜬 것과 같다. 커밋한
     * 것으로 친 배치는 그대로다.
     *
     * @param extendedWindow 되감은 상태가 모자랄 때 늘려 되감는 창
     * @param events 커밋 뒤 사건을 받을 뒤 단계
     */
    void start(Duration window, Duration extendedWindow, PipelineEvents events) {
        meters = new SimpleMeterRegistry();
        waitingTasks.clear();
        laneHeld = false;
        ReplaySource replays = (statistics, from, through, throughBatchId) -> {
            if (replayFailures > 0) {
                replayFailures--;
                return ReplaySource.Read.failed(REPLAY_FAILURE);
            }
            return source.read(statistics, from, through, throughBatchId);
        };
        ForecastAssembly.Sources sources = new ForecastAssembly.Sources(replays, source, committed,
            this::activeRuntime, this::versions, stopsByVersion::get);
        ForecastAssembly.Settings settings = new ForecastAssembly.Settings(
            new ForecastPolicy(Duration.ofMinutes(10), batchLimit), window, extendedWindow, false);
        mainline = new ForecastAssembly(jdbc, beans.transactions(), clock, meters).forecast(this::submit, events,
            sources, capacities, failingRecords(beans.records()), beans.passes(), settings);
        clock.at = EVENING.plus(Duration.ofDays(2));
        mainline.pickUp();
    }

    void start() {
        start(RouteStateReplayer.DEFAULT_WINDOW, RouteStateReplayer.DEFAULT_EXTENDED_WINDOW, new NoEvents());
    }

    private boolean submit(String routeKey, Runnable task) {
        if (laneHeld) {
            waitingTasks.add(task);
            return true;
        }
        task.run();
        return true;
    }

    private Optional<RuntimeSnapshot> activeRuntime() {
        if (!modelActive) {
            return Optional.empty();
        }
        return Optional.of(runtime);
    }

    /** 한 번에 읽는 배치 수의 한도를 정한다. {@link #start}를 부르기 전에 쓴다. */
    void batchLimit(final int limit) {
        batchLimit = limit;
    }

    /** 이제부터 줄에 넣은 일을 바로 실행하지 않고 기다리게 한다. {@link #runNextTask()}로 하나씩 실행한다. */
    void holdLane() {
        laneHeld = true;
    }

    /** 줄에서 기다리는 일의 수. */
    int waitingTasks() {
        return waitingTasks.size();
    }

    /** 줄에서 가장 오래 기다린 일 하나를 실행한다. */
    void runNextTask() {
        waitingTasks.remove().run();
    }

    /** 활성 모델이 있는지를 정한다. */
    void modelActive(final boolean active) {
        modelActive = active;
    }

    /** 진단 수신자를 붙인다. {@link #start}를 부른 뒤에 쓴다. */
    void telemetry(ForecastTelemetry telemetry) {
        mainline.setTelemetry(telemetry);
    }

    /** 노선 상태를 검증된 출발 지점으로 둔다. 흐름의 첫 배치부터 처리할 때 쓴다. */
    void fromVerifiedOrigin() {
        mainline.states().put(statistics(), RouteForecastState.fromVerifiedOrigin(stream.route()));
    }

    /** 예외로 실패한 뒤처럼 메모리의 노선 상태를 버린다. */
    void dropState() {
        mainline.states().remove(stream.route());
    }

    /** 수집이 배치를 커밋했다고 치고 그 신호를 {@code at} 시각에 넣는다. */
    void signal(final long batchId, Instant at) {
        clock.at = at;
        committed.commit(batchId);
        mainline.batchStored(batchId, meta.get(batchId).version());
    }

    /** 수집이 배치를 커밋했다고 치되 신호는 넣지 않는다. 잃은 신호다. */
    void commit(final long batchId) {
        committed.commit(batchId);
    }

    /** 다음 처리하지 않은 배치 조회가 배치를 읽은 직후에 한 번 실행할 일을 정한다. */
    void afterNextPendingRead(Runnable task) {
        committed.afterNextFind(task);
    }

    /** 커밋한 것으로 친 배치를 모두 잊는다. 예보 기록을 지우고 흐름을 처음부터 다시 처리할 때 쓴다. */
    void rewindCollection() {
        committed.forgetAll();
    }

    /** {@code at} 시각에 주기 줍기를 한 번 돌린다. */
    void pickUp(Instant at) {
        clock.at = at;
        mainline.pickUp();
    }

    /** 흐름이 정한 착수 시각에 신호를 넣는다. */
    void signal(final long batchId) {
        signal(batchId, intended(batchId));
    }

    /** 그 배치의 예보 실행 저장을 {@code times}번 실패시킨다. */
    void failSaves(final long batchId, final int times) {
        saveFailures.put(batchId, times);
    }

    /** 다음 되감기 읽기를 한 번 실패시킨다. */
    void failNextReplay() {
        replayFailures = 1;
    }

    /** 수집 결과가 성공(줄 있음·빈 응답)인 배치를 처리 순서로. 예보가 처리할 대상이다. */
    List<Long> batches() {
        return stream.batches().stream().filter(batchId -> !meta.get(batchId).outcome().startsWith("FAILED"))
            .toList();
    }

    /**
     * 처리 순서에서 {@code from}번째 뒤로, 확정 조건을 만족하고 수신 간격이 30초인 배치가 {@code count}개 이어지는 첫 구간이다.
     */
    List<Long> consecutiveConfirmed(final int from, final int count) {
        List<Long> batches = batches();
        for (int start = from; start + count <= batches.size(); start++) {
            List<Long> run = batches.subList(start, start + count);
            if (isConsecutiveConfirmed(run)) {
                return List.copyOf(run);
            }
        }
        throw new IllegalStateException("확정 배치가 " + count + "개 이어지는 구간이 없다");
    }

    private boolean isConsecutiveConfirmed(List<Long> run) {
        for (int index = 0; index < run.size(); index++) {
            if (!meetsConfirmation(run.get(index))) {
                return false;
            }
            if (index > 0 && !receivedAt(run.get(index)).equals(receivedAt(run.get(index - 1)).plusSeconds(30))) {
                return false;
            }
        }
        return true;
    }

    boolean meetsConfirmation(final long batchId) {
        Meta batch = meta.get(batchId);
        return "LIVE".equals(batch.origin()) && "SUCCESS_ROWS".equals(batch.outcome())
            && !intended(batchId).isAfter(batch.receivedAt().plusSeconds(90));
    }

    Instant receivedAt(final long batchId) {
        return meta.get(batchId).receivedAt();
    }

    /** 흐름이 정한 착수 시각. 마이크로초 자리까지만 있다. */
    Instant intended(final long batchId) {
        return intendedConfirmation.get(batchId);
    }

    List<Long> versions() {
        return List.copyOf(stream.stopsByVersion().keySet());
    }

    RouteStops stops() {
        return stopsByVersion.values().iterator().next();
    }

    AlightingAwareSeatForecastModel model() {
        return model;
    }

    AlightingAwareRouteStatistics statistics() {
        return model.statistics(stops());
    }

    Optional<RouteForecastState> state() {
        return mainline.states().find(statistics());
    }

    /** 그 배치의 예보 실행 결과. 실행 줄이 없으면 비어 있다. */
    Optional<String> runOutcome(final long batchId) {
        return jdbc.sql("SELECT outcome FROM forecast_run WHERE observation_batch_id=?").param(batchId)
            .query(String.class).optional();
    }

    /** 그 배치의 예보 실행 줄 수. */
    long runCount(final long batchId) {
        return jdbc.sql("SELECT count(*) FROM forecast_run WHERE observation_batch_id=?").param(batchId)
            .query(Long.class).single();
    }

    /** 그 배치의 예보 실행에 적힌 착수 시각. */
    Instant runConfirmedAt(final long batchId) {
        return jdbc.sql("SELECT confirmed_at FROM forecast_run WHERE observation_batch_id=?").param(batchId)
            .query(OffsetDateTime.class).single().toInstant();
    }

    /** {@code salmonbus.pipeline.forecast.<name>}의 결과 태그별 수. */
    double counter(String name, String result) {
        return countOf(meters.find("salmonbus.pipeline.forecast." + name).tag("result", result).counter());
    }

    /** 태그 없는 {@code salmonbus.pipeline.forecast.<name>}의 수. */
    double counter(String name) {
        return countOf(meters.find("salmonbus.pipeline.forecast." + name).counter());
    }

    private static double countOf(Counter counter) {
        if (counter == null) {
            return 0;
        }
        return counter.count();
    }

    private ForecastRecordStore failingRecords(ForecastRecordStore delegate) {
        return (ForecastRecordStore) Proxy.newProxyInstance(ForecastRecordStore.class.getClassLoader(),
            new Class<?>[] {ForecastRecordStore.class}, (proxy, method, arguments) -> {
                if ("save".equals(method.getName()) && arguments[0] instanceof ForecastPublished published) {
                    failIfArmed(published.observationBatchId());
                }
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private void failIfArmed(final long batchId) {
        final int left = saveFailures.getOrDefault(batchId, 0);
        if (left == 0) {
            return;
        }
        saveFailures.put(batchId, left - 1);
        throw new IllegalStateException("시험이 넣은 저장 실패");
    }

    private AlightingAwareSeatForecastModel model(Random random) {
        AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
            PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
            PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
            PipelineTestSupport.stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
        Map<String, ExtendedAlightingPredictor> extension = Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
            PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
            PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)));
        return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
            Map.of(MODEL_ROUTE, evidence(stream.statistics())), PipelineTestSupport.digest(), extension);
    }

    /** 정류장마다 정답 계산이 읽는 근거(실정류장, 회차, 휴일, 시간 칸 계수, 정류장 계수)를 만든다. */
    private static Map<Integer, String> evidence(AlightingAwareRouteStatistics statistics) {
        String parameters = "\"p\":0.12,\"lam\":[1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9],\"kappa\":1,\"r\":2";
        String cells = AlightingTimeCell.extendedKeys().stream()
            .map(key -> "\"%s\":{%s}".formatted(key, parameters)).collect(Collectors.joining(","));
        Map<Integer, String> evidence = new HashMap<>();
        for (int order = 1; order <= ObservationStreamFixture.STOP_COUNT; order++) {
            evidence.put(order, """
                {"turn":%d,"realStopOrders":%s,"holidays":[],"extendedSettlement":{%s},
                 "alighting":{"stops":[{"stop":%d,"band":"morning",%s},{"stop":%d,"band":"evening",%s}]}}
                """.formatted(statistics.turn(), statistics.realStopOrders(), cells, order, parameters, order,
                parameters));
        }
        return evidence;
    }

    private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
        String digest = selected.bundleDigest();
        ModelRelease release = ModelRelease.alighting("reinforce-" + digest.substring(0, 8), digest,
            EVENING.minusSeconds(86400).toString(), new SupportedForecastScope(List.of(MODEL_ROUTE)), selected);
        LoadedModelRegistry registry = new LoadedModelRegistry();
        new BundleActivation(beans.deployments(), registry).activate(() -> release);
        return new ActiveForecastRuntimeResolver(beans.deployments(), registry).resolveActive().orElseThrow();
    }

    /** 시험이 주입받아 넘기는 것들이다. */
    record Beans(
        JdbcClient jdbc,
        PlatformTransactionManager transactions,
        ModelDeploymentRepository deployments,
        ForecastRecordStore records,
        StopPassStore passes
    ) {
    }

    private record Meta(long version, Instant receivedAt, String origin, String outcome) {
    }

    /** 사건을 받아 아무것도 하지 않는 뒤 단계다. */
    static final class NoEvents implements PipelineEvents {
        @Override
        public void batchSeen(ObservationBatchSeen seen) {
        }

        @Override
        public void forecastPublished(ForecastPublished published) {
        }

        @Override
        public void passesClosed(List<PassClosed> passes) {
        }
    }

    /** 사건마다 두 뒤 단계에 차례로 넘긴다. */
    record BothEvents(PipelineEvents first, PipelineEvents second) implements PipelineEvents {
        @Override
        public void batchSeen(ObservationBatchSeen seen) {
            first.batchSeen(seen);
            second.batchSeen(seen);
        }

        @Override
        public void forecastPublished(ForecastPublished published) {
            first.forecastPublished(published);
            second.forecastPublished(published);
        }

        @Override
        public void passesClosed(List<PassClosed> passes) {
            first.passesClosed(passes);
            second.passesClosed(passes);
        }
    }

    /** 뒤 단계가 받은 통과 사건을 받은 순서대로 적어 둔다. */
    static final class RecordedPasses implements PipelineEvents {
        final List<String> keys = new ArrayList<>();

        @Override
        public void batchSeen(ObservationBatchSeen seen) {
        }

        @Override
        public void forecastPublished(ForecastPublished published) {
        }

        @Override
        public void passesClosed(List<PassClosed> passes) {
            passes.forEach(pass -> keys.add(pass.tripStartObservationId() + ":" + pass.stopOrder()));
        }
    }
}
