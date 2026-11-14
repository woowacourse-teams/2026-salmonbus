package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
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
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
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
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.infrastructure.alighting.JdbcAlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 예보 경로를 본선({@code forecast.pipeline=v2})으로 돌렸을 때를 실제 DB로 본다. 비교 기준은 같은 흐름을 옛 발행 경로로 처리한
 * 결과다. 본선의 착수 시각은 시계에서 찍으므로, 배치마다 옛 경로가 찍었을 착수 시각을 시계에 넣어 두 경로의 조건을 맞춘다.
 *
 * <p>노선별 실행 줄은 넣는 즉시 같은 스레드에서 실행해 배치 순서를 시험이 정한다. 옛 경로의 따로 커밋하는 트랜잭션은 시험 트랜잭션에
 * 합류시킨다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastPipelineMainlineTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T10:00:00Z");
    private static final String SOURCE_ROUTE_ID = "234001739";
    private static final String MODEL_ROUTE = "6801";
    private static final int MAXIMUM_ROWS = 250_000;
    private static final List<String> LEGACY_TABLES = List.of("seat_forecast", "forecast_publication",
        "alighting_forecast_evaluation_work", "alighting_forecast_evaluation_result", "alighting_settlement_batch_work",
        "alighting_history_checkpoint");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ForecastBatchCommitter committer;
    @Autowired
    RouteWorkAccess routeWork;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @TempDir
    Path checkpoint;
    private VehicleCapacityCache cache;

    @BeforeEach
    void setup() {
        cache = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
    }

    @Test
    void 본선이_새_테이블만_채우고_옛_테이블과_체크포인트를_건드리지_않으며_옛_경로와_예측값이_비트까지_같다() throws IOException {
        // given
        Harness harness = new Harness(20261201L);
        harness.startMainline(Duration.ofHours(6), 20);
        harness.mainlineFromVerifiedOrigin();
        Map<String, Long> legacyBefore = legacyCounts();

        // when
        harness.batches().forEach(harness::mainline);

        // then
        assertThat(legacyCounts()).isEqualTo(legacyBefore);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM observation_batch WHERE route_version_id IN (:versions) AND input_confirmed_at IS NOT NULL
            """).param("versions", harness.versions()).query(Long.class).single()).isZero();
        try (Stream<Path> files = Files.list(checkpoint)) {
            assertThat(files).isEmpty();
        }
        assertThat(runCount(harness)).isEqualTo(harness.batches().size());
        assertThat(jdbc.sql("SELECT count(*) FROM stop_pass WHERE route_id=?").param(harness.stream.routeId())
            .query(Long.class).single()).isGreaterThan(50);
        assertThat(harness.counter("batches", "published")).isGreaterThan(300);
        assertThat(harness.counter("batches", "failed")).isZero();
        assertThat(harness.events.published).isEqualTo(harness.batches().size());

        // when
        harness.batches().forEach(harness::legacy);

        // then
        Comparison comparison = compare(harness, Set.copyOf(harness.batches()));
        assertThat(comparison.same()).isGreaterThan(2_000);
        assertThat(comparison.valueDiffers()).isZero();
        assertThat(comparison.legacyOnly()).isEmpty();
        assertThat(comparison.newOnly()).isZero();
        assertRunOutcomes(harness);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 옛_경로로_처리하다_본선으로_넘어가면_되감기가_두_출처를_이어_처음부터_옛_경로로_처리한_값과_같다(final boolean shadow) {
        // given
        Harness harness = new Harness(20261202L);
        if (shadow) {
            harness.enableShadow();
        }
        List<Long> batches = harness.batches();
        List<Long> before = batches.subList(0, batches.size() / 2);
        List<Long> after = batches.subList(batches.size() / 2, batches.size());

        // when
        before.forEach(harness::legacy);
        harness.disableShadow();
        harness.startMainline(Duration.ofHours(6), 20);
        after.forEach(harness::mainline);
        after.forEach(harness::legacy);

        // then
        Comparison comparison = compare(harness, Set.copyOf(after));
        assertThat(harness.counter("replays", "succeeded")).isOne();
        assertThat(comparison.same()).isGreaterThan(1_000);
        assertThat(comparison.valueDiffers()).isZero();
        assertThat(comparison.newOnly()).isZero();
        assertThat(comparison.legacyOnly()).isEmpty();
        assertThat(harness.excluded()).isZero();
        if (shadow) {
            assertThat(jdbc.sql("""
                SELECT count(*) FROM forecast_run r JOIN observation_batch b ON b.id=r.observation_batch_id
                WHERE b.id IN (:batches) AND r.confirmed_at<>b.input_confirmed_at
                """).param("batches", before).query(Long.class).single()).isZero();
        }
    }

    @Test
    void 되감기_창이_짧으면_전환_직후_빠지는_차량은_사유와_함께_세고_나머지_예측값은_옛_경로와_같다() {
        // given
        Harness harness = new Harness(20261205L);
        List<Long> batches = harness.batches();
        List<Long> before = batches.subList(0, batches.size() / 2);
        List<Long> after = batches.subList(batches.size() / 2, batches.size());

        // when
        before.forEach(harness::legacy);
        harness.startMainline(Duration.ofMinutes(45), 20);
        after.forEach(harness::mainline);
        after.forEach(harness::legacy);

        // then
        Comparison comparison = compare(harness, Set.copyOf(after));
        assertThat(harness.excluded()).isPositive();
        assertThat(comparison.legacyOnly()).isNotEmpty();
        assertThat(harness.excludedObservations).containsAll(comparison.legacyOnly());
        assertThat(comparison.valueDiffers()).isZero();
        assertThat(comparison.newOnly()).isZero();
        assertThat(comparison.same()).isGreaterThan(1_000);
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
        assertThat(jdbc.sql("SELECT outcome||':'||prediction_count FROM forecast_run WHERE observation_batch_id=?")
            .param(late).query(String.class).single()).isEqualTo("LATE:0");
        assertThat(harness.cursor()).isEqualTo(cursorBefore);
        assertThat(new JdbcPendingPipelineBatches(jdbc).find(harness.version(late),
            harness.receivedAt(late).minusSeconds(60), 100)).noneMatch(pending -> pending.observationBatchId() == late);
        ReplaySource.Read replay = new JdbcReplaySource(jdbc, MAXIMUM_ROWS, JdbcReplaySource.Axis.RECORDED_RUN)
            .read(harness.statistics(), harness.receivedAt(late).minus(Duration.ofHours(1)),
                harness.receivedAt(late).plus(Duration.ofHours(1)), Long.MAX_VALUE);
        assertThat(replay.batches()).isNotEmpty().noneMatch(batch -> batch.observationBatchId() == late);
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
        assertThat(runs.keySet()).containsExactlyInAnyOrderElementsOf(batches);
        assertThat(runs.values()).allMatch(count -> count == 1);
        assertThat(harness.counter("replays", "succeeded")).isOne();
        assertThat(harness.counter("batches", "already_processed")).isOne();
        assertThat(harness.counter("batches", "failed")).isZero();
        assertThat(jdbc.sql("""
            SELECT count(*) FROM forecast_run WHERE observation_batch_id IN (:lost) AND confirmed_at=:at
            """).param("lost", lost).param("at", pickedAt.atOffset(ZoneOffset.UTC)).query(Long.class).single())
            .isEqualTo(lost.size());
    }

    private Map<String, Long> legacyCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : LEGACY_TABLES) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    private long runCount(Harness harness) {
        return jdbc.sql("SELECT count(*) FROM forecast_run WHERE route_id=?").param(harness.stream.routeId())
            .query(Long.class).single();
    }

    private Comparison compare(Harness harness, Set<Long> batches) {
        Map<String, double[]> legacy = new HashMap<>();
        Map<String, Long> legacyObservations = new HashMap<>();
        jdbc.sql("""
            SELECT f.vehicle_observation_id,f.target_stop_order,f.expected_seats,f.seat_full_chance FROM seat_forecast f
              JOIN vehicle_observation o ON o.id=f.vehicle_observation_id
            WHERE o.observation_batch_id IN (:batches)
            """).param("batches", batches).query(row -> {
                String key = row.getLong(1) + ":" + row.getInt(2);
                legacy.put(key, new double[] {row.getDouble(3), row.getDouble(4)});
                legacyObservations.put(key, row.getLong(1));
            });
        Map<String, double[]> fresh = new HashMap<>();
        jdbc.sql("""
            SELECT r.vehicle_observation_id,t.target,t.seats,t.probability FROM forecast_record r,
              unnest(r.target_stop_orders,r.expected_seats,r.full_probabilities) AS t(target,seats,probability)
            WHERE r.observation_batch_id IN (:batches)
            """).param("batches", batches).query(row -> {
                fresh.put(row.getLong(1) + ":" + row.getInt(2), new double[] {row.getDouble(3), row.getDouble(4)});
            });
        int same = 0;
        int differs = 0;
        int newOnly = 0;
        Set<Long> legacyOnly = new HashSet<>();
        for (Map.Entry<String, double[]> entry : fresh.entrySet()) {
            double[] other = legacy.get(entry.getKey());
            if (other == null) {
                newOnly++;
            } else if (Double.doubleToLongBits(other[0]) == Double.doubleToLongBits(entry.getValue()[0])
                && Double.doubleToLongBits(other[1]) == Double.doubleToLongBits(entry.getValue()[1])) {
                same++;
            } else {
                differs++;
            }
        }
        legacy.keySet().stream().filter(key -> !fresh.containsKey(key))
            .forEach(key -> legacyOnly.add(legacyObservations.get(key)));
        return new Comparison(same, differs, newOnly, legacyOnly);
    }

    private void assertRunOutcomes(Harness harness) {
        Map<String, Long> outcomes = new HashMap<>();
        jdbc.sql("""
            SELECT CASE
                WHEN b.ingestion_origin<>'LIVE' THEN 'INPUT_UNAVAILABLE'
                WHEN b.outcome='SUCCESS_EMPTY' OR b.stored_rows=0 THEN 'EMPTY'
                WHEN b.input_confirmed_at>b.response_received_at+interval '90 seconds' THEN 'LATE'
                WHEN p.prediction_count>0 THEN 'PUBLISHED'
                ELSE 'EMPTY' END AS expected, r.outcome, count(*)
            FROM forecast_run r JOIN observation_batch b ON b.id=r.observation_batch_id
              LEFT JOIN forecast_publication p ON p.source_batch_id=r.observation_batch_id
            WHERE r.route_id=?
            GROUP BY 1,2
            """).param(harness.stream.routeId()).query(row -> {
                outcomes.put(row.getString(1) + "->" + row.getString(2), row.getLong(3));
            });
        assertThat(outcomes.keySet()).allSatisfy(pair -> {
            String[] sides = pair.split("->");
            assertThat(sides[1]).as(pair).isEqualTo(sides[0]);
        });
        assertThat(outcomes).containsKeys("EMPTY->EMPTY", "LATE->LATE", "PUBLISHED->PUBLISHED");
        Map<Long, Integer> legacyCounts = new HashMap<>();
        jdbc.sql("SELECT source_batch_id,prediction_count FROM forecast_publication WHERE route_version_id IN (:versions)")
            .param("versions", harness.versions())
            .query(row -> {
                legacyCounts.put(row.getLong(1), row.getInt(2));
            });
        Map<Long, Integer> freshCounts = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,prediction_count FROM forecast_run WHERE route_id=?")
            .param(harness.stream.routeId())
            .query(row -> {
                if (legacyCounts.containsKey(row.getLong(1))) {
                    freshCounts.put(row.getLong(1), row.getInt(2));
                }
            });
        assertThat(freshCounts).isEqualTo(legacyCounts);
    }

    private record Comparison(int same, int valueDiffers, int newOnly, Set<Long> legacyOnly) {
    }

    /** 흐름 하나와 옛 경로·그림자·본선을 묶는다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final Map<Long, Meta> meta = new HashMap<>();
        private final PipelineTestSupport.MutableClock clock = new PipelineTestSupport.MutableClock();
        private final ForecastBatchWriter writer;
        private final RuntimeSnapshot runtime;
        private final AlightingAwareSeatForecastModel model;
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final RecordingEvents events = new RecordingEvents();
        private final Set<Long> excludedObservations = new HashSet<>();
        private ForecastPipelineService mainline;
        private ShadowForecastService shadow;

        private Harness(final long seed) {
            stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofHours(4), Duration.ofSeconds(30), null,
                seed, MODEL_ROUTE);
            String origins = ObservationStreamFixture.origins(jdbc, stream);
            jdbc.sql("""
                SELECT id,input_confirmed_at,route_version_id,response_received_at,ingestion_origin,outcome
                FROM observation_batch WHERE route_version_id IN (:versions)
                """).param("versions", versions())
                .query(row -> {
                    intendedConfirmation.put(row.getLong(1), row.getObject(2, OffsetDateTime.class).toInstant());
                    meta.put(row.getLong(1), new Meta(row.getLong(3), row.getObject(4, OffsetDateTime.class).toInstant(),
                        row.getString(5), row.getString(6)));
                });
            jdbc.sql("UPDATE observation_batch SET input_confirmed_at=NULL WHERE route_version_id IN (:versions)")
                .param("versions", versions()).update();
            stream.stopsByVersion().forEach((version, stops) -> stopsByVersion.put(version,
                new RouteStops(version, SOURCE_ROUTE_ID, stops.stops(), stops.routeName())));
            model = model(seed);
            runtime = activate(model);
            writer = new ForecastBatchWriter(committer, clock, routeWork);
            writer.setAlightingAwareFeatures(new JdbcAlightingAwareFeatureQuery(jdbc, cache, origins, MAXIMUM_ROWS, false,
                checkpoint.toString()));
            writer.setConfirmedInput(new ConfirmedAlightingAwareInput(jdbc, PipelineTestSupport.joining(transactions)));
        }

        /** 본선을 새로 띄운다. 노선 상태가 없는 채로 시작하므로 첫 배치가 되감는다. */
        private void startMainline(Duration replayWindow, final int batchLimit) {
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS, JdbcReplaySource.Axis.RECORDED_RUN);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            PipelineBatchProcessor processor = new PipelineBatchProcessor("pipeline_forecast", stage,
                new RouteStateReplayer(source, stage, replayWindow), records, passes,
                new TransactionTemplate(transactions), List.of(events), new RouteStateRegistry(), false);
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

        private void enableShadow() {
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            shadow = new ShadowForecastService(ShadowForecastTask::run, stage,
                new RouteStateReplayer(source, stage, Duration.ofHours(6)), source, records, passes,
                new TransactionTemplate(transactions), List.of(), new RouteStateRegistry(), false, Clock.systemUTC(),
                new SimpleMeterRegistry());
            shadow.states().put(statistics(), RouteForecastState.fromVerifiedOrigin(stream.route()));
            writer.setLegacyForecastListener(shadow);
        }

        private void disableShadow() {
            writer.setLegacyForecastListener(null);
        }

        /** 수집 결과가 성공(줄 있음·빈 응답)인 배치를 처리 순서로. 본선이 처리할 대상이다. */
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
                Meta batch = meta.get(batchId);
                if ("LIVE".equals(batch.origin()) && "SUCCESS_ROWS".equals(batch.outcome())
                    && !intendedConfirmation.get(batchId).isAfter(batch.receivedAt().plusSeconds(90))) {
                    confirmed.add(batchId);
                }
            }
            return confirmed.get(index);
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

        /** 본선이 그 배치에서 예보하지 않은 좌석을 아는 차량 관측이다. 대조 제외를 관측 단위로 확인하는 데 쓴다. */
        private List<Long> withheldObservations(final long batchId) {
            return jdbc.sql("""
                SELECT o.id FROM vehicle_observation o
                WHERE o.observation_batch_id=? AND o.vehicle_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM forecast_record r WHERE r.vehicle_observation_id=o.id)
                """).param(batchId).query(Long.class).list();
        }

        /** 옛 경로로 처리한다. 정상 수집이 아닌 배치는 옛 경로가 확정하지 못해 실패하므로 건너뛴다. */
        private void legacy(final long batchId) {
            Meta batch = meta.get(batchId);
            if (!"LIVE".equals(batch.origin())) {
                return;
            }
            clock.at = intendedConfirmation.get(batchId);
            writer.writeForecastsOf(new PendingForecastBatch(batchId, batch.version(), stream.routeId(),
                batch.receivedAt()), stopsByVersion.get(batch.version()), runtime);
        }

        /** 되감은 직후라 예보에서 뺀 차량 관측 수(두 사유의 합). */
        private double excluded() {
            return counter("excluded", ForecastStage.TRIP_START_UNKNOWN)
                + counter("excluded", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
        }

        private double counter(String name, String tag) {
            String key = "excluded".equals(name) ? "reason" : "result";
            Counter counter = meters.find("salmonbus.pipeline.forecast." + name).tag(key, tag).counter();
            return counter == null ? 0 : counter.count();
        }

        private AlightingAwareSeatForecastModel model(final long seed) {
            Random random = new Random(seed);
            AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
                PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
                PipelineTestSupport.stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
                PipelineTestSupport.stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
            Map<Integer, String> evidence = new HashMap<>();
            for (int order = 1; order <= ObservationStreamFixture.STOP_COUNT; order++) {
                evidence.put(order, "{}");
            }
            Map<String, ExtendedAlightingPredictor> extension = Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
                PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
                PipelineTestSupport.stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)));
            return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
                Map.of(MODEL_ROUTE, evidence), PipelineTestSupport.digest(), extension);
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
