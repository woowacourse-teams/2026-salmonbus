package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.ObservationBatchRead;
import com.gustler.backend.forecasting.application.pipeline.ObservationBatchReader;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
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
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistorySeedReader;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.infrastructure.alighting.JdbcAlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관측 흐름을 옛 발행 경로({@link ForecastBatchWriter})로 실제 처리하면서 그림자 실행을 켜고, 새 경로의 기록이 옛 발행과 같은지 본다.
 *
 * <p>옛 경로의 입력 확정은 따로 커밋하는 트랜잭션을 쓰는데, 관측 흐름은 시험 트랜잭션 안에만 있으므로 그 트랜잭션에 합류시킨다.
 * 그림자 실행 줄은 넣는 즉시 같은 스레드에서 실행해 배치 순서를 시험이 정한다.
 */
@ForecastingIntegrationTest
@Transactional
class ShadowForecastPipelineTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant DAWN = Instant.parse("2026-10-15T20:00:00Z");
    private static final String SOURCE_ROUTE_ID = "234001739";
    private static final String MODEL_ROUTE = "6801";
    private static final int MAXIMUM_ROWS = 250_000;

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
    void 옛_경로로_처리한_흐름에서_그림자의_기록이_옛_발행과_같은_예측과_통과를_담는다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(4), EVENING.plus(Duration.ofHours(2)), 20261115L, true,
            false);
        harness.startFromVerifiedOrigin();

        // when
        Set<String> expectedPasses = harness.processAll(batchId -> { }, true);

        // then
        assertThat(harness.counter("predictions", "same")).isGreaterThan(2_000);
        assertThat(harness.counter("predictions", "value_differs")).isZero();
        assertThat(harness.counter("predictions", "legacy_only")).isZero();
        assertThat(harness.counter("predictions", "new_only")).isZero();
        assertThat(harness.counter("predictions", "excluded")).isZero();
        assertThat(harness.counter("batches", "mismatch")).isZero();
        assertThat(harness.counter("batches", "failed")).isZero();
        assertThat(harness.counter("batches", "match")).isGreaterThan(400);
        assertSamePredictions(harness);
        assertRunOutcomes(harness);
        assertThat(stopPasses(harness)).isEqualTo(expectedPasses).hasSizeGreaterThan(50);
        assertThat(harness.events.published).isEqualTo(harness.legacyBatches);
        assertThat(harness.events.seen).isEqualTo(harness.legacyBatches);
        assertThat(harness.events.passes).isEqualTo(expectedPasses.size());
        assertThat(harness.service.states().routes()).isOne();
        assertThat(harness.service.states().observations()).isPositive();
    }

    @Test
    void 출퇴근_범위_밖_배치는_새_경로도_범위_밖으로_남기고_범위_안_예측은_같다() {
        // given
        Harness harness = new Harness(DAWN, Duration.ofMinutes(120), null, 7L, false, true);
        harness.startFromVerifiedOrigin();

        // when
        harness.processAll(batchId -> { }, false);

        // then
        final long outside = jdbc.sql("""
            SELECT count(*) FROM forecast_run WHERE route_version_id IN (:versions) AND outcome='OUT_OF_SCOPE'
            """).param("versions", harness.versions()).query(Long.class).single();
        assertThat(outside).isGreaterThan(80);
        assertThat(harness.counter("predictions", "same")).isGreaterThan(300);
        assertThat(harness.counter("predictions", "value_differs") + harness.counter("predictions", "legacy_only")
            + harness.counter("predictions", "new_only")).isZero();
        assertThat(harness.counter("batches", "mismatch")).isZero();
        assertSamePredictions(harness);
    }

    @Test
    void 중간에_상태를_버려도_되감은_뒤_값_다름이_없고_대조_제외만_잠시_생긴다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(4), null, 20261116L, true, false,
            Duration.ofMinutes(45));
        harness.startFromVerifiedOrigin();
        final long restartAt = harness.confirmedBatch(150);

        // when
        harness.processAll(batchId -> {
            if (batchId == restartAt) {
                harness.service.states().remove(harness.stream.route());
            }
        }, false);

        // then
        assertThat(harness.counter("replays", "succeeded")).isOne();
        assertThat(harness.counter("predictions", "excluded")).isPositive();
        assertThat(harness.counter("predictions", "value_differs")).isZero();
        assertThat(harness.counter("predictions", "legacy_only")).isZero();
        assertThat(harness.counter("predictions", "new_only")).isZero();
        assertThat(harness.counter("batches", "mismatch")).isZero();
        assertThat(harness.firstExcludedBatch).isGreaterThanOrEqualTo(restartAt);
    }

    @Test
    void 줄이_가득_차_버린_배치_뒤에는_상태를_버리고_다음_배치에서_되감아_회복한다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(3), null, 20261117L, true, false);
        harness.startFromVerifiedOrigin();
        final long dropped = harness.confirmedBatch(200);
        harness.discard = dropped;

        // when
        harness.processAll(batchId -> { }, false);

        // then
        assertThat(harness.counter("dropped")).isOne();
        assertThat(harness.counter("replays", "succeeded")).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_run WHERE observation_batch_id=?").param(dropped)
            .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_publication WHERE source_batch_id=?").param(dropped)
            .query(Long.class).single()).isOne();
        assertThat(harness.counter("predictions", "value_differs") + harness.counter("predictions", "legacy_only")
            + harness.counter("predictions", "new_only")).isZero();
        assertThat(harness.counter("predictions", "same")).isGreaterThan(1_000);
    }

    @Test
    void 그림자_안에서_예외가_나면_그_노선_상태를_버리고_옛_발행은_그대로다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(3), null, 20261118L, true, false);
        harness.startFromVerifiedOrigin();
        harness.failingRead = harness.confirmedBatch(150);

        // when
        harness.processAll(batchId -> { }, false);

        // then
        assertThat(harness.counter("batches", "failed")).isOne();
        assertThat(harness.counter("replays", "succeeded")).isOne();
        assertThat(harness.legacyFailures).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_publication WHERE route_version_id IN (:versions)")
            .param("versions", harness.versions()).query(Long.class).single()).isEqualTo(harness.legacyBatches);
        assertThat(harness.counter("predictions", "value_differs") + harness.counter("predictions", "legacy_only")
            + harness.counter("predictions", "new_only")).isZero();
    }

    @Test
    void 되감기가_실패하면_그_노선은_착수_시각_기준_5분_동안_다시_되감지_않는다() {
        // given
        Harness harness = new Harness(EVENING, Duration.ofHours(2), null, 20261119L, true, false);
        harness.startFromVerifiedOrigin();
        final long broken = harness.confirmedBatch(20);
        final long restartAt = harness.confirmedBatch(60);

        // when
        harness.processAll(batchId -> {
            if (batchId == restartAt) {
                jdbc.sql("UPDATE observation_batch SET stored_rows=stored_rows+1 WHERE id=?").param(broken).update();
                harness.service.states().remove(harness.stream.route());
            }
        }, false);

        // then
        final double failed = harness.counter("replays", "failed");
        final double deferred = harness.counter("batches", "replay_deferred");
        assertThat(failed).isGreaterThanOrEqualTo(2);
        assertThat(deferred).isGreaterThanOrEqualTo((failed - 1) * 6);
        assertThat(harness.counter("replays", "succeeded")).isZero();
        assertThat(harness.counter("predictions", "value_differs")).isZero();
        assertThat(harness.counter("predictions", "legacy_only")).isPositive();
        assertThat(jdbc.sql("""
            SELECT count(*) FROM forecast_run WHERE route_version_id IN (:versions) AND outcome='INPUT_UNAVAILABLE'
            """).param("versions", harness.versions()).query(Long.class).single())
            .isEqualTo((long) (failed + deferred));
    }

    private void assertSamePredictions(Harness harness) {
        Map<String, double[]> legacy = new HashMap<>();
        jdbc.sql("""
            SELECT vehicle_observation_id,target_stop_order,expected_seats,seat_full_chance FROM seat_forecast
            WHERE route_version_id IN (:versions)
            """).param("versions", harness.versions()).query(row -> {
                legacy.put(row.getLong(1) + ":" + row.getInt(2), new double[] {row.getDouble(3), row.getDouble(4)});
            });
        Map<String, double[]> fresh = new HashMap<>();
        jdbc.sql("""
            SELECT r.vehicle_observation_id,t.target,t.seats,t.probability FROM forecast_record r,
              unnest(r.target_stop_orders,r.expected_seats,r.full_probabilities) AS t(target,seats,probability)
            WHERE r.route_version_id IN (:versions)
            """).param("versions", harness.versions()).query(row -> {
                fresh.put(row.getLong(1) + ":" + row.getInt(2), new double[] {row.getDouble(3), row.getDouble(4)});
            });
        assertThat(fresh.keySet()).isEqualTo(legacy.keySet()).isNotEmpty();
        legacy.forEach((key, values) -> {
            double[] other = fresh.get(key);
            assertThat(Double.doubleToLongBits(other[0])).as("기대 좌석 %s", key)
                .isEqualTo(Double.doubleToLongBits(values[0]));
            assertThat(Double.doubleToLongBits(other[1])).as("만석 확률 %s", key)
                .isEqualTo(Double.doubleToLongBits(values[1]));
        });
        Map<Long, Integer> legacyCounts = new HashMap<>();
        jdbc.sql("SELECT source_batch_id,prediction_count FROM forecast_publication WHERE route_version_id IN (:versions)")
            .param("versions", harness.versions())
            .query(row -> {
                legacyCounts.put(row.getLong(1), row.getInt(2));
            });
        Map<Long, Integer> freshCounts = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,prediction_count FROM forecast_run WHERE route_version_id IN (:versions)")
            .param("versions", harness.versions())
            .query(row -> {
                freshCounts.put(row.getLong(1), row.getInt(2));
            });
        assertThat(freshCounts).isEqualTo(legacyCounts);
    }

    private void assertRunOutcomes(Harness harness) {
        Map<String, Long> outcomes = new HashMap<>();
        jdbc.sql("""
            SELECT CASE
                WHEN b.outcome='SUCCESS_EMPTY' OR b.stored_rows=0 THEN 'EMPTY'
                WHEN b.input_confirmed_at>b.response_received_at+interval '90 seconds' THEN 'LATE'
                WHEN p.prediction_count>0 THEN 'PUBLISHED'
                ELSE 'EMPTY' END AS expected, r.outcome, count(*)
            FROM forecast_run r JOIN observation_batch b ON b.id=r.observation_batch_id
              JOIN forecast_publication p ON p.source_batch_id=r.observation_batch_id
            WHERE r.route_version_id IN (:versions)
            GROUP BY 1,2
            """).param("versions", harness.versions()).query(row -> {
                outcomes.put(row.getString(1) + "->" + row.getString(2), row.getLong(3));
            });
        assertThat(outcomes.keySet()).allSatisfy(pair -> {
            String[] sides = pair.split("->");
            assertThat(sides[1]).as(pair).isEqualTo(sides[0]);
        });
        assertThat(outcomes).containsKeys("EMPTY->EMPTY", "LATE->LATE", "PUBLISHED->PUBLISHED");
    }

    private Set<String> stopPasses(Harness harness) {
        Set<String> keys = new HashSet<>();
        jdbc.sql("SELECT trip_start_observation_id,stop_order,closure_observation_id FROM stop_pass WHERE route_id=?")
            .param(harness.stream.routeId())
            .query(row -> {
                keys.add(row.getLong(1) + ":" + row.getInt(2) + ":" + row.getLong(3));
            });
        return keys;
    }

    private static String passKey(AlightingAwarePassEvent event) {
        return event.tripId() + ":" + event.stopOrder() + ":" + event.confirmation().observationId();
    }

    /** 입력 확정의 새 트랜잭션을 시험 트랜잭션에 합류시킨다. 같은 SQL을 그대로 쓴다. */
    private static PlatformTransactionManager joining(PlatformTransactionManager delegate) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                DefaultTransactionDefinition joined = new DefaultTransactionDefinition(definition);
                joined.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                return delegate.getTransaction(joined);
            }

            @Override
            public void commit(TransactionStatus status) {
                delegate.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                delegate.rollback(status);
            }
        };
    }

    private static NumericTreeEnsemble stumps(final int features, NumericTreeEnsemble.Kind kind, double[] baseline,
        Random random) {
        double[] thresholds = {0.0, 0.5, 1, 2, 5, 10, 30};
        List<List<List<NumericTreeEnsemble.Node>>> iterations = new ArrayList<>();
        for (int iteration = 0; iteration < 24; iteration++) {
            List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
            for (int tree = 0; tree < baseline.length; tree++) {
                trees.add(List.of(
                    new NumericTreeEnsemble.Node(random.nextInt(features), thresholds[random.nextInt(thresholds.length)],
                        random.nextBoolean(), 1, 2, 0, false),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true)));
            }
            iterations.add(trees);
        }
        return new NumericTreeEnsemble(features, kind, baseline, iterations);
    }

    /** 흐름 하나와 옛 경로·그림자 실행을 묶는다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final MutableClock clock = new MutableClock();
        private final ForecastBatchWriter writer;
        private final ShadowForecastService service;
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final RecordingEvents events = new RecordingEvents();
        private final RuntimeSnapshot runtime;
        private final AlightingAwareSeatForecastModel model;
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final AlightingAwareHistorySeedReader seeds;
        private long discard = -1;
        private long failingRead = -1;
        private long firstExcludedBatch = -1;
        private int legacyBatches;
        private int legacyFailures;

        private Harness(Instant start, Duration length, Instant versionSwitch, final long seed, final boolean extended,
            final boolean rushOnly) {
            this(start, length, versionSwitch, seed, extended, rushOnly, Duration.ofHours(6));
        }

        private Harness(Instant start, Duration length, Instant versionSwitch, final long seed, final boolean extended,
            final boolean rushOnly, Duration replayWindow) {
            stream = ObservationStreamFixture.insert(jdbc, start, length, Duration.ofSeconds(30), versionSwitch, seed,
                MODEL_ROUTE);
            String origins = ObservationStreamFixture.origins(jdbc, stream);
            jdbc.sql("SELECT id,input_confirmed_at FROM observation_batch WHERE route_version_id IN (:versions)")
                .param("versions", versions())
                .query(row -> {
                    intendedConfirmation.put(row.getLong(1), row.getObject(2, OffsetDateTime.class).toInstant());
                });
            jdbc.sql("UPDATE observation_batch SET input_confirmed_at=NULL WHERE route_version_id IN (:versions)")
                .param("versions", versions()).update();
            stream.stopsByVersion().forEach((version, stops) -> stopsByVersion.put(version,
                new RouteStops(version, SOURCE_ROUTE_ID, stops.stops(), stops.routeName())));
            model = model(seed, extended);
            runtime = activate(model);
            JdbcAlightingAwareFeatureQuery query = new JdbcAlightingAwareFeatureQuery(jdbc, cache, origins, MAXIMUM_ROWS,
                rushOnly, checkpoint.toString());
            seeds = AlightingAwareHistorySeedReader.confirmed(checkpoint.toString(), MAXIMUM_ROWS);
            writer = new ForecastBatchWriter(committer, clock, routeWork);
            writer.setAlightingAwareFeatures(query);
            writer.setConfirmedInput(new ConfirmedAlightingAwareInput(jdbc, joining(transactions)));
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            ObservationBatchReader reader = batchId -> {
                if (batchId == failingRead) {
                    throw new IllegalStateException("읽기 실패 흉내");
                }
                Optional<ObservationBatchRead> read = source.readBatch(batchId);
                return read;
            };
            service = new ShadowForecastService(this::submit, stage,
                new RouteStateReplayer(source, stage, replayWindow), reader, records, passes,
                new TransactionTemplate(transactions), List.of(events), new RouteStateRegistry(), rushOnly,
                Clock.systemUTC(), meters);
            writer.setLegacyForecastListener(service);
        }

        private void startFromVerifiedOrigin() {
            service.states().put(model.statistics(stopsByVersion.values().iterator().next()),
                RouteForecastState.fromVerifiedOrigin(stream.route()));
        }

        private void submit(ShadowForecastTask task) {
            if (task.observationBatchId() == discard) {
                task.discard();
                return;
            }
            final double excludedBefore = counter("predictions", "excluded");
            task.run();
            if (counter("predictions", "excluded") > excludedBefore && firstExcludedBatch < 0) {
                firstExcludedBatch = task.observationBatchId();
            }
        }

        private List<Long> versions() {
            return List.copyOf(stream.stopsByVersion().keySet());
        }

        /** 처리 순서에서 {@code index}번째로 확정 조건을 만족하는 배치다. */
        private long confirmedBatch(final int index) {
            List<Long> confirmed = new ArrayList<>();
            for (final long batchId : stream.batches()) {
                Meta batch = meta(batchId);
                if ("LIVE".equals(batch.origin()) && "SUCCESS_ROWS".equals(batch.outcome())
                    && !intendedConfirmation.get(batchId).isAfter(batch.receivedAt().plusSeconds(90))) {
                    confirmed.add(batchId);
                }
            }
            return confirmed.get(index);
        }

        private Set<String> processAll(LongConsumer beforeBatch, final boolean collectPasses) {
            Set<String> expectedPasses = new HashSet<>();
            for (final long batchId : stream.batches()) {
                Meta batch = meta(batchId);
                if (!"LIVE".equals(batch.origin()) || batch.outcome().startsWith("FAILED")) {
                    continue;
                }
                final long version = batch.version();
                beforeBatch.accept(batchId);
                clock.at = intendedConfirmation.get(batchId);
                PendingForecastBatch pending = new PendingForecastBatch(batchId, version, stream.routeId(),
                    batch.receivedAt());
                try {
                    writer.writeForecastsOf(pending, stopsByVersion.get(version), runtime);
                    legacyBatches++;
                } catch (RuntimeException failure) {
                    legacyFailures++;
                    continue;
                }
                if (collectPasses) {
                    AlightingAwareHistorySeedReader.Seed seed = seeds.read(version);
                    if (seed != null && seed.batchId() == batchId) {
                        seed.events().stream().filter(event -> event.confirmation().batchId() == batchId)
                            .forEach(event -> expectedPasses.add(passKey(event)));
                    }
                }
            }
            return expectedPasses;
        }

        private Meta meta(final long batchId) {
            return jdbc.sql("""
                SELECT route_version_id,response_received_at,ingestion_origin,outcome FROM observation_batch WHERE id=?
                """).param(batchId).query((row, index) -> new Meta(row.getLong(1),
                    row.getObject(2, OffsetDateTime.class).toInstant(), row.getString(3), row.getString(4)))
                .single();
        }

        private double counter(String name, String result) {
            Counter counter = meters.find("salmonbus.pipeline.shadow." + name).tag("result", result).counter();
            return counter == null ? 0 : counter.count();
        }

        private double counter(String name) {
            Counter counter = meters.find("salmonbus.pipeline.shadow." + name).counter();
            return counter == null ? 0 : counter.count();
        }

        private AlightingAwareSeatForecastModel model(final long seed, final boolean extended) {
            Random random = new Random(seed);
            AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
                stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
                stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
                stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
            Map<Integer, String> evidence = new HashMap<>();
            for (int order = 1; order <= ObservationStreamFixture.STOP_COUNT; order++) {
                evidence.put(order, "{}");
            }
            Map<String, ExtendedAlightingPredictor> extension = extended
                ? Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
                    stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
                    stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)))
                : Map.of();
            return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
                Map.of(MODEL_ROUTE, evidence), digest(), extension);
        }

        private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
            String digest = selected.bundleDigest();
            ModelRelease release = ModelRelease.alighting("shadow-" + digest.substring(0, 8), digest,
                EVENING.minusSeconds(86400).toString(), new SupportedForecastScope(List.of(MODEL_ROUTE)), selected);
            LoadedModelRegistry registry = new LoadedModelRegistry();
            new BundleActivation(deployments, registry).activate(() -> release);
            return new ActiveForecastRuntimeResolver(deployments, registry).resolveActive().orElseThrow();
        }
    }

    private static String digest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] bytes = new byte[32];
        ByteBuffer.wrap(bytes).putLong(first.getMostSignificantBits()).putLong(first.getLeastSignificantBits())
            .putLong(second.getMostSignificantBits()).putLong(second.getLeastSignificantBits());
        return HexFormat.of().formatHex(bytes);
    }

    private record Meta(long version, Instant receivedAt, String origin, String outcome) {
    }

    /** 옛 경로의 착수 시각을 배치마다 정한다. */
    private static final class MutableClock extends Clock {
        private Instant at = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
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
