package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PipelineBatchProcessor;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ReplaySource;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateRegistry;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예보 경로가 관측 흐름을 처리해 저장한 예측값·실행 결과·닫힌 통과를 실제 DB로 본다. 기대값은 기준 구현(dev {@code e98ae3ea}의
 * 발행)이 같은 흐름에 낸 값이다({@link ForecastValueBaseline}). 착수 시각은 시계에서 찍으므로, 배치마다 흐름이 정한 착수 시각을
 * 시계에 넣어 기대값과 조건을 맞춘다.
 *
 * <p>노선별 실행 줄은 넣는 즉시 같은 스레드에서 실행해 배치 순서를 시험이 정한다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastPipelineMainlineTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant DAWN = Instant.parse("2026-10-15T20:00:00Z");
    private static final String SOURCE_ROUTE_ID = "234001739";
    private static final String MODEL_ROUTE = "6801";
    private static final int MAXIMUM_ROWS = 250_000;
    private static final List<String> UNTOUCHED_TABLES = List.of("seat_forecast", "forecast_publication",
        "alighting_forecast_evaluation_work", "alighting_forecast_evaluation_result", "alighting_settlement_batch_work",
        "alighting_history_checkpoint");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    private VehicleCapacityCache cache;

    @BeforeEach
    void setup() {
        cache = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
    }

    @Test
    void 첫_배치부터_처리하면_예보_기록만_채우고_예측값과_실행_결과가_기준_기록과_같다() {
        // given
        Harness harness = new Harness(20261201L);
        ForecastValueBaseline baseline = ForecastValueBaseline.read("forecast/mainline-20261201");
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();
        Map<String, Long> untouchedBefore = untouchedCounts();

        // when
        harness.batches().forEach(harness::mainline);

        // then
        ForecastValueBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, harness.batches());
        assertSoftly(softly -> {
            softly.assertThat(untouchedCounts()).isEqualTo(untouchedBefore);
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM observation_batch
                WHERE route_version_id IN (:versions) AND input_confirmed_at IS NOT NULL
                """).param("versions", harness.versions()).query(Long.class).single()).isZero();
            softly.assertThat(runCount(harness)).isEqualTo(harness.batches().size());
            softly.assertThat(jdbc.sql("SELECT count(*) FROM stop_pass WHERE route_id=?")
                .param(harness.stream.routeId())
                .query(Long.class).single()).isGreaterThan(50);
            softly.assertThat(harness.counter("batches", "published")).isGreaterThan(300);
            softly.assertThat(harness.counter("batches", "failed")).isZero();
            softly.assertThat(harness.events.published).isEqualTo(harness.batches().size());
            softly.assertThat(comparison.same()).isGreaterThan(2_000);
            softly.assertThat(comparison.valueDiffers()).isZero();
            softly.assertThat(comparison.baselineOnly()).isEmpty();
            softly.assertThat(comparison.newOnly()).isZero();
        });
        assertRunOutcomes(harness, baseline);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 입력_확정_시각만_남은_배치와_실행_기록이_있는_배치를_되감기가_이어_그_뒤의_예측값이_기준_기록과_같다(final boolean recorded) {
        // given
        Harness harness = new Harness(20261202L, Duration.ofHours(3));
        ForecastValueBaseline baseline = ForecastValueBaseline.read("forecast/transition-20261202");
        List<Long> batches = harness.batches();
        List<Long> before = batches.subList(0, batches.size() / 2);
        List<Long> after = batches.subList(batches.size() / 2, batches.size());
        if (recorded) {
            harness.startMainline(Duration.ofHours(6), 20);
            harness.mainlineFromVerifiedOrigin();
            before.forEach(harness::mainline);
        }
        harness.confirmInputs(baseline);

        // when
        harness.startMainline(Duration.ofHours(6), 20);
        after.forEach(harness::mainline);

        // then
        ForecastValueBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, after);
        assertSoftly(softly -> {
            softly.assertThat(harness.counter("replays", "succeeded")).isOne();
            softly.assertThat(comparison.same()).isGreaterThan(1_000);
            softly.assertThat(comparison.valueDiffers()).isZero();
            softly.assertThat(comparison.newOnly()).isZero();
            softly.assertThat(comparison.baselineOnly()).isEmpty();
            softly.assertThat(harness.excluded()).isZero();
            if (recorded) {
                softly.assertThat(jdbc.sql("""
                    SELECT count(*) FROM forecast_run r JOIN observation_batch b ON b.id=r.observation_batch_id
                    WHERE b.id IN (:batches) AND r.confirmed_at<>b.input_confirmed_at
                    """).param("batches", before).query(Long.class).single()).isZero();
            }
        });
    }

    @Test
    void 되감기_창이_짧으면_되감은_직후_빠지는_차량은_사유와_함께_세고_나머지_예측값은_기준_기록과_같다() {
        // given
        Harness harness = new Harness(20261205L);
        ForecastValueBaseline baseline = ForecastValueBaseline.read("forecast/short-replay-20261205");
        List<Long> batches = harness.batches();
        List<Long> after = batches.subList(batches.size() / 2, batches.size());
        harness.confirmInputs(baseline);

        // when
        harness.startMainline(Duration.ofMinutes(45), 20);
        after.forEach(harness::mainline);

        // then
        ForecastValueBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, after);
        assertSoftly(softly -> {
            softly.assertThat(harness.excluded()).isPositive();
            softly.assertThat(comparison.baselineOnly()).isNotEmpty();
            softly.assertThat(harness.excludedObservations).containsAll(comparison.baselineOnly());
            softly.assertThat(comparison.valueDiffers()).isZero();
            softly.assertThat(comparison.newOnly()).isZero();
            softly.assertThat(comparison.same()).isGreaterThan(1_000);
        });
    }

    @Test
    void 노선_판본이_바뀌는_흐름에서도_예측값과_실행_결과와_닫힌_통과가_기준_기록과_같다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(4), EVENING.plus(Duration.ofHours(2)), 20261115L, true,
            false);
        ForecastValueBaseline baseline = ForecastValueBaseline.read("forecast/version-switch-20261115");
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();

        // when
        harness.batches().forEach(harness::mainline);

        // then
        ForecastValueBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, harness.batches());
        assertSoftly(softly -> {
            softly.assertThat(comparison.same()).isGreaterThan(2_000);
            softly.assertThat(comparison.valueDiffers()).isZero();
            softly.assertThat(comparison.baselineOnly()).isEmpty();
            softly.assertThat(comparison.newOnly()).isZero();
            softly.assertThat(harness.excluded()).isZero();
            softly.assertThat(harness.counter("batches", "failed")).isZero();
            softly.assertThat(stopPasses(harness)).isEqualTo(baseline.passes()).hasSizeGreaterThan(50);
            softly.assertThat(harness.events.published).isEqualTo(harness.batches().size());
            softly.assertThat(harness.events.seen).isEqualTo(harness.batches().size());
            softly.assertThat(harness.events.passes).isEqualTo(baseline.passes().size());
            softly.assertThat(harness.mainline.states().routes()).isOne();
            softly.assertThat(harness.mainline.states().observations()).isPositive();
        });
        assertRunOutcomes(harness, baseline);
    }

    @Test
    void 시간_확장_모델이_없으면_출퇴근_범위_밖_배치는_범위_밖으로_남기고_범위_안_예측값은_기준_기록과_같다() {
        // given
        Harness harness = new Harness(DAWN, Duration.ofMinutes(120), null, 7L, false, true);
        ForecastValueBaseline baseline = ForecastValueBaseline.read("forecast/rush-only-7");
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();

        // when
        harness.batches().forEach(harness::mainline);

        // then
        ForecastValueBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, harness.batches());
        final long outside = jdbc.sql("""
            SELECT count(*) FROM forecast_run WHERE route_version_id IN (:versions) AND outcome='OUT_OF_SCOPE'
            """).param("versions", harness.versions()).query(Long.class).single();
        assertSoftly(softly -> {
            softly.assertThat(outside).isGreaterThan(80);
            softly.assertThat(comparison.same()).isGreaterThan(300);
            softly.assertThat(comparison.valueDiffers()).isZero();
            softly.assertThat(comparison.baselineOnly()).isEmpty();
            softly.assertThat(comparison.newOnly()).isZero();
            softly.assertThat(predictionCounts(harness, baseline)).isEqualTo(baseline.predictionCounts());
        });
    }

    @Test
    void 되감기가_실패하면_그_노선은_착수_시각_기준_5분_동안_다시_되감지_않고_예보_없는_실행만_남긴다() {
        // given
        Harness harness = new Harness(20261119L, Duration.ofHours(2));
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();
        final long broken = harness.confirmedBatch(20);
        final long restartAt = harness.confirmedBatch(60);
        List<Long> batches = harness.batches();
        List<Long> head = batches.subList(0, batches.indexOf(restartAt));
        List<Long> tail = batches.subList(batches.indexOf(restartAt), batches.size());
        head.forEach(harness::mainline);

        // when
        jdbc.sql("UPDATE observation_batch SET stored_rows=stored_rows+1 WHERE id=?").param(broken).update();
        harness.mainline.states().remove(harness.stream.route());
        tail.forEach(harness::mainline);

        // then
        final double failed = harness.counter("replays", "failed");
        final double deferred = harness.counter("batches", "replay_deferred");
        assertSoftly(softly -> {
            softly.assertThat(failed).isGreaterThanOrEqualTo(2);
            softly.assertThat(harness.counter("batches", "replay_failed")).isEqualTo(failed);
            softly.assertThat(deferred).isGreaterThanOrEqualTo((failed - 1) * 6);
            softly.assertThat(harness.counter("replays", "succeeded")).isZero();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM forecast_run r JOIN observation_batch b ON b.id=r.observation_batch_id
                WHERE r.route_id=? AND b.ingestion_origin='LIVE' AND r.outcome='INPUT_UNAVAILABLE'
                """).param(harness.stream.routeId()).query(Long.class).single()).isEqualTo((long) (failed + deferred));
            softly.assertThat(jdbc.sql("SELECT count(*) FROM forecast_record WHERE observation_batch_id IN (:batches)")
                .param("batches", head).query(Long.class).single()).isPositive();
            softly.assertThat(jdbc.sql("SELECT count(*) FROM forecast_record WHERE observation_batch_id IN (:batches)")
                .param("batches", tail).query(Long.class).single()).isZero();
        });
    }

    @Test
    void 수신_뒤_90초를_넘겨_착수한_배치는_LATE로_남기고_상태에_넣지_않으며_다시_줍지_않는다() {
        // given
        Harness harness = new Harness(20261203L);
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();
        final long late = harness.confirmedBatch(80);
        List<Long> batches = harness.batches();
        batches.subList(0, batches.indexOf(late)).forEach(harness::mainline);
        Optional<RouteForecastState.Cursor> cursorBefore = harness.cursor();

        // when
        harness.mainlineAt(late, harness.receivedAt(late).plusSeconds(91));

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql(
                    "SELECT outcome||':'||prediction_count FROM forecast_run WHERE observation_batch_id=?")
                .param(late).query(String.class).single()).isEqualTo("LATE:0");
            softly.assertThat(harness.cursor()).isEqualTo(cursorBefore);
            softly.assertThat(new JdbcPendingPipelineBatches(jdbc).find(harness.version(late),
                harness.receivedAt(late).minusSeconds(60), 100))
                .noneMatch(pending -> pending.observationBatchId() == late);
            ReplaySource.Read replay = new JdbcReplaySource(jdbc, MAXIMUM_ROWS)
                .read(harness.statistics(), harness.receivedAt(late).minus(Duration.ofHours(1)),
                    harness.receivedAt(late).plus(Duration.ofHours(1)), Long.MAX_VALUE);
            softly.assertThat(replay.batches()).isNotEmpty().noneMatch(batch -> batch.observationBatchId() == late);
        });
    }

    @Test
    void 처리_중에_상태를_버리고_신호를_잃어도_줍기로_이어_가면_빠지거나_두_번_저장되는_배치가_없다() {
        // given
        Harness harness = new Harness(20261204L);
        harness.startMainline(Duration.ofHours(6), 5);
        harness.mainlineFromVerifiedOrigin();
        List<Long> batches = harness.batches();
        List<Long> head = batches.subList(0, 100);
        List<Long> lost = batches.subList(100, 105);
        List<Long> tail = batches.subList(105, batches.size());
        head.forEach(harness::mainline);

        // when
        harness.mainline.states().remove(harness.stream.route());
        Instant pickedAt = harness.receivedAt(lost.getLast()).plusSeconds(20);
        harness.clock.at = pickedAt;
        harness.mainline.pickUp();
        tail.forEach(harness::mainline);
        harness.mainline(head.getFirst());

        // then
        Map<Long, Long> runs = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,count(*) FROM forecast_run WHERE route_id=? GROUP BY 1")
            .param(harness.stream.routeId())
            .query(row -> {
                runs.put(row.getLong(1), row.getLong(2));
            });
        assertSoftly(softly -> {
            softly.assertThat(runs.keySet()).containsExactlyInAnyOrderElementsOf(batches);
            softly.assertThat(runs.values()).allMatch(count -> count == 1);
            softly.assertThat(harness.counter("replays", "succeeded")).isOne();
            softly.assertThat(harness.counter("batches", "already_processed")).isOne();
            softly.assertThat(harness.counter("batches", "failed")).isZero();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM forecast_run WHERE observation_batch_id IN (:lost) AND confirmed_at=:at
                """).param("lost", lost).param("at", pickedAt.atOffset(ZoneOffset.UTC)).query(Long.class).single())
                .isEqualTo(lost.size());
        });
    }

    private Map<String, Long> untouchedCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : UNTOUCHED_TABLES) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    private long runCount(Harness harness) {
        return jdbc.sql("SELECT count(*) FROM forecast_run WHERE route_id=?").param(harness.stream.routeId())
            .query(Long.class).single();
    }

    private Set<String> stopPasses(Harness harness) {
        Set<String> stored = new HashSet<>();
        jdbc.sql("SELECT trip_start_observation_id,stop_order,closure_observation_id FROM stop_pass WHERE route_id=?")
            .param(harness.stream.routeId())
            .query(row -> {
                stored.add(harness.keys.observation(row.getLong(1)) + " " + row.getInt(2) + " "
                    + harness.keys.observation(row.getLong(3)));
            });
        return stored;
    }

    /** 실행 결과가 배치마다 기대값과 같고, 예측을 내는 배치의 예측 수가 기대값과 같은지 본다. */
    private void assertRunOutcomes(Harness harness, ForecastValueBaseline baseline) {
        Map<String, Long> outcomes = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,outcome FROM forecast_run WHERE route_id=?")
            .param(harness.stream.routeId()).query(row -> {
                ForecastValueBaseline.Run expected = baseline.runs().get(harness.keys.batch(row.getLong(1)));
                outcomes.merge(expected.outcome() + "->" + row.getString(2), 1L, Long::sum);
            });
        assertThat(outcomes.keySet()).allSatisfy(pair -> {
            String[] sides = pair.split("->");
            assertThat(sides[1]).as(pair).isEqualTo(sides[0]);
        });
        assertThat(outcomes).containsKeys("EMPTY->EMPTY", "LATE->LATE", "PUBLISHED->PUBLISHED");
        assertThat(predictionCounts(harness, baseline)).isEqualTo(baseline.predictionCounts());
    }

    /** 기대값이 예측 수를 적어 둔 배치의 저장된 예측 수다. */
    private Map<Integer, Integer> predictionCounts(Harness harness, ForecastValueBaseline baseline) {
        Map<Integer, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,prediction_count FROM forecast_run WHERE route_id=?")
            .param(harness.stream.routeId())
            .query(row -> {
                final int ordinal = harness.keys.batch(row.getLong(1));
                if (baseline.predictionCounts().containsKey(ordinal)) {
                    counts.put(ordinal, row.getInt(2));
                }
            });
        return counts;
    }

    /** 흐름 하나와 예보 경로를 묶는다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final StreamKeys keys;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final Map<Long, Meta> meta = new HashMap<>();
        private final PipelineTestSupport.MutableClock clock = new PipelineTestSupport.MutableClock();
        private final RuntimeSnapshot runtime;
        private final AlightingAwareSeatForecastModel model;
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final RecordingEvents events = new RecordingEvents();
        private final Set<Long> excludedObservations = new HashSet<>();
        private final boolean rushOnly;
        private SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private ForecastPipelineService mainline;

        private Harness(final long seed) {
            this(seed, Duration.ofHours(4));
        }

        private Harness(final long seed, Duration length) {
            this(EVENING, length, null, seed, true, false);
        }

        /**
         * @param versionSwitch 노선 판본이 바뀌는 시각. 비어 있으면 한 판본이다
         * @param extended 시간 확장 모델을 함께 둘지
         * @param rushOnly 시간 확장 모델이 없을 때 출퇴근 범위 밖 배치를 예보하지 않을지
         */
        private Harness(
            Instant start,
            Duration length,
            Instant versionSwitch,
            final long seed,
            final boolean extended,
            final boolean rushOnly
        ) {
            this.rushOnly = rushOnly;
            stream = ObservationStreamFixture.insert(jdbc, start, length, Duration.ofSeconds(30), versionSwitch,
                seed, MODEL_ROUTE);
            keys = StreamKeys.of(jdbc, stream);
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
            model = model(seed, extended);
            runtime = activate(model);
        }

        /** 예보 경로를 새로 띄운다. 노선 상태와 지표가 빈 채로 시작하므로 첫 배치가 되감는다. */
        private void startMainline(Duration replayWindow, final int batchLimit) {
            meters = new SimpleMeterRegistry();
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            PipelineBatchProcessor processor = new PipelineBatchProcessor("pipeline_forecast", stage,
                new RouteStateReplayer(source, stage, replayWindow), records, passes,
                new TransactionTemplate(transactions), List.of(events), new RouteStateRegistry(), rushOnly);
            mainline = new ForecastPipelineService((key, task) -> {
                task.run();
                return true;
            }, processor, source, new JdbcPendingPipelineBatches(jdbc), () -> Optional.of(runtime), this::versions,
                stopsByVersion::get, new JdbcSettlementContextWriter(jdbc),
                new ForecastPolicy(Duration.ofMinutes(10), batchLimit, 3000, 400), clock, meters);
            clock.at = EVENING.plus(Duration.ofDays(2));
            mainline.pickUp();
        }

        private void mainlineFromVerifiedOrigin() {
            mainline.states().put(statistics(), RouteForecastState.fromVerifiedOrigin(stream.route()));
        }

        /**
         * 기대값 파일에 적힌 배치에 입력 확정 시각을 넣는다. 예보 실행 기록이 없는 배치는 되감기가 이 시각을 착수 시각으로 읽는다.
         */
        private void confirmInputs(ForecastValueBaseline baseline) {
            baseline.confirmed().forEach((ordinal, at) -> jdbc.sql(
                    "UPDATE observation_batch SET input_confirmed_at=? WHERE id=?")
                .params(at.atOffset(ZoneOffset.UTC), keys.batchId(ordinal)).update());
        }

        /** 수집 결과가 성공(줄 있음·빈 응답)인 배치를 처리 순서로. 예보 경로가 처리할 대상이다. */
        private List<Long> batches() {
            return stream.batches().stream().filter(batchId -> !meta.get(batchId).outcome().startsWith("FAILED"))
                .toList();
        }

        private List<Long> versions() {
            return List.copyOf(stream.stopsByVersion().keySet());
        }

        private AlightingAwareRouteStatistics statistics() {
            return model.statistics(stopsByVersion.values().iterator().next());
        }

        private Optional<RouteForecastState.Cursor> cursor() {
            return mainline.states().find(statistics()).flatMap(RouteForecastState::cursor);
        }

        private Instant receivedAt(final long batchId) {
            return meta.get(batchId).receivedAt();
        }

        private long version(final long batchId) {
            return meta.get(batchId).version();
        }

        /** 처리 순서에서 {@code index}번째로 확정 조건을 만족하는 배치다. */
        private long confirmedBatch(final int index) {
            List<Long> confirmed = new ArrayList<>();
            for (final long batchId : stream.batches()) {
                if (meetsConfirmation(batchId)) {
                    confirmed.add(batchId);
                }
            }
            return confirmed.get(index);
        }

        private boolean meetsConfirmation(final long batchId) {
            Meta batch = meta.get(batchId);
            return "LIVE".equals(batch.origin()) && "SUCCESS_ROWS".equals(batch.outcome())
                && !intendedConfirmation.get(batchId).isAfter(batch.receivedAt().plusSeconds(90));
        }

        private void mainline(final long batchId) {
            mainlineAt(batchId, intendedConfirmation.get(batchId));
        }

        private void mainlineAt(final long batchId, Instant at) {
            clock.at = at;
            final double before = excluded();
            mainline.batchStored(batchId, version(batchId));
            if (excluded() > before) {
                excludedObservations.addAll(withheldObservations(batchId));
            }
        }

        /** 그 배치에서 예보하지 않은, 차량 번호가 있는 관측이다. 되감은 직후 빠진 차량을 관측 단위로 확인하는 데 쓴다. */
        private List<Long> withheldObservations(final long batchId) {
            return jdbc.sql("""
                SELECT o.id FROM vehicle_observation o
                WHERE o.observation_batch_id=? AND o.vehicle_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM forecast_record r WHERE r.vehicle_observation_id=o.id)
                """).param(batchId).query(Long.class).list();
        }

        /** 되감은 직후라 예보에서 뺀 차량 관측 수(두 사유의 합). */
        private double excluded() {
            return counter("excluded", ForecastStage.TRIP_START_UNKNOWN)
                + counter("excluded", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
        }

        private double counter(String name, String tag) {
            String key = counterTagKey(name);
            Counter counter = meters.find("salmonbus.pipeline.forecast." + name).tag(key, tag).counter();
            return countOf(counter);
        }

        private String counterTagKey(String name) {
            if ("excluded".equals(name)) {
                return "reason";
            }
            return "result";
        }

        private double countOf(Counter counter) {
            if (counter == null) {
                return 0;
            }
            return counter.count();
        }

        private AlightingAwareSeatForecastModel model(final long seed, final boolean extended) {
            Random random = new Random(seed);
            AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
                PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
                PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
                PipelineTestSupport.stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
            Map<Integer, String> evidence = new HashMap<>();
            for (int order = 1; order <= ObservationStreamFixture.STOP_COUNT; order++) {
                evidence.put(order, "{}");
            }
            return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
                Map.of(MODEL_ROUTE, evidence), PipelineTestSupport.digest(), extension(extended, random));
        }

        /** 시간 확장 모델을 둘 때만 난수로 트리를 만든다. */
        private Map<String, ExtendedAlightingPredictor> extension(final boolean extended, Random random) {
            if (!extended) {
                return Map.of();
            }
            return Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
                PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
                PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)));
        }

        private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
            String digest = selected.bundleDigest();
            ModelRelease release = ModelRelease.alighting("mainline-" + digest.substring(0, 8), digest,
                EVENING.minusSeconds(86400).toString(), new SupportedForecastScope(List.of(MODEL_ROUTE)), selected);
            LoadedModelRegistry registry = new LoadedModelRegistry();
            new BundleActivation(deployments, registry).activate(() -> release);
            return new ActiveForecastRuntimeResolver(deployments, registry).resolveActive().orElseThrow();
        }
    }

    private record Meta(long version, Instant receivedAt, String origin, String outcome) {
    }

    private static final class RecordingEvents implements PipelineEvents {
        private int seen;
        private int published;
        private int passes;

        @Override
        public void batchSeen(ObservationBatchSeen batch) {
            seen++;
        }

        @Override
        public void forecastPublished(ForecastPublished batch) {
            published++;
        }

        @Override
        public void passesClosed(List<PassClosed> closed) {
            passes += closed.size();
        }
    }
}
