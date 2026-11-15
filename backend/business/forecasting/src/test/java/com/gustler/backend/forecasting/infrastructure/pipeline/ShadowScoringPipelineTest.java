package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateRegistry;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastService;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
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
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.infrastructure.alighting.JdbcAlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.jdbc.ConfirmedPassLabelCalculator;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcAlightingAwareForecastEvaluation;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * 관측 흐름을 옛 경로(옛 발행 + 옛 정산)와 그림자(새 예보 + 새 채점)로 함께 돌리고, 대조 작업이 예측마다 같은 결과를 내는지 본다.
 *
 * <p>옛 발행은 운영과 같이 정산 근거를 모델 배포에 한 번만 적는다({@code forecast.compact-settlement-write=true}). 옛 정산은
 * 흐름을 다 넣은 뒤 마지막 배치 3시간 뒤의 시계로 돌려 모든 예측을 마감 시각까지 본다(옛 정산의 날짜 정리가 흐름의 날짜를
 * 지난날로 보지 않게 한다). 옛 정산은 대기가 있는 모든 노선을 도므로, 다른 시험이 남긴 대기는 시험 트랜잭션 안에서 지워
 * (끝나면 롤백된다) 이 노선만 정산하게 한다. 옛 정산의 배치 꺼내기
 * ({@code UPDATE … FROM (… LIMIT 1 FOR UPDATE SKIP LOCKED)})는 중첩 반복 계획이 잡히면 한 번에 여러 배치를 꺼내 실패하므로,
 * 시험 트랜잭션 안에서만 중첩 반복 계획을 끈다. 옛 경로의 따로 커밋하는 트랜잭션은 시험 트랜잭션에 합류시키고, 그림자 실행 줄과
 * 채점 줄은 넣는 즉시 같은 스레드에서 실행한다.
 */
@ForecastingIntegrationTest
@Transactional
class ShadowScoringPipelineTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T09:30:00Z");
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
    JdbcForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;
    @TempDir
    Path checkpoint;
    private VehicleCapacityCache cache;

    @BeforeEach
    void 차량_정원_캐시를_준비한다() {
        cache = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
    }

    @Test
    void 옛_정산과_그림자_채점을_함께_돌리면_대조_작업이_예측마다_같다고_본다() {
        // given
        Harness harness = new Harness(20261120L);

        // when
        harness.processAll(-1, -1);
        JdbcScoringComparison.Report report = harness.settleAndCompare();

        // then
        assertEquivalent(report);
        assertSoftly(softly -> {
            softly.assertThat(report.count(JdbcScoringComparison.Result.SAME)).isGreaterThan(1_000);
            softly.assertThat(states(harness)).containsKeys("SETTLED", "UNSUPPORTED", "LOST")
                .doesNotContainKey("PENDING");
            softly.assertThat(legacySettledWithHash(harness)).isPositive();
        });
    }

    @Test
    void 모델_배포가_바뀌는_순간의_통과는_배포마다_정답을_따로_내고_옛_정산과_같다() {
        // given
        Harness harness = new Harness(20261121L);
        final long switchAt = harness.stream.batches().get(harness.stream.batches().size() / 2);

        // when
        harness.processAll(switchAt, -1);
        JdbcScoringComparison.Report report = harness.settleAndCompare();

        // then
        assertEquivalent(report);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("""
                SELECT count(DISTINCT model_deployment_id) FROM pass_label WHERE route_id = ?
                """).param(harness.stream.routeId()).query(Long.class).single()).isEqualTo(2);
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT source_date, trip_start_observation_id, stop_order, capacity FROM pass_label
                    WHERE route_id = ? AND state = 'SETTLED'
                    GROUP BY 1, 2, 3, 4 HAVING count(DISTINCT model_deployment_id) = 2
                       AND count(DISTINCT parameter_hash) = 2) shared
                """).param(harness.stream.routeId()).query(Long.class).single()).isPositive();
        });
    }

    @Test
    void 채점_줄이_넘쳐_사건을_버려도_저장된_기록을_되감아_옛_정산과_같다() {
        // given
        Harness harness = new Harness(20261122L);
        final long dropAt = harness.stream.batches().get(harness.stream.batches().size() / 3);

        // when
        harness.processAll(-1, dropAt);
        JdbcScoringComparison.Report report = harness.settleAndCompare();

        // then
        assertEquivalent(report);
        assertSoftly(softly -> {
            softly.assertThat(harness.scoringMeters.find("salmonbus.pipeline.scoring.dropped").counter().count())
                .isPositive();
            softly.assertThat(harness.scoringMeters.find("salmonbus.pipeline.scoring.replays").tag("reason", "dropped")
                .tag("result", "succeeded").counter().count()).isPositive();
        });
    }

    private static void assertEquivalent(JdbcScoringComparison.Report report) {
        assertSoftly(softly -> {
            softly.assertThat(report.unexplained())
                .as("설명되지 않는 차이: %s", report.unexplained().stream().limit(10).map(String::valueOf)
                    .collect(Collectors.joining("\n")))
                .isEmpty();
            softly.assertThat(report.count(JdbcScoringComparison.Result.LEGACY_ONLY)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.NEW_ONLY)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.PENDING)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.SAME)).isPositive();
        });
    }

    private Map<String, Long> states(Harness harness) {
        Map<String, Long> states = new HashMap<>();
        jdbc.sql("SELECT state, count(*) FROM forecast_outcome WHERE route_id = ? GROUP BY state")
            .param(harness.stream.routeId())
            .query(row -> {
                states.put(row.getString(1), row.getLong(2));
            });
        return states;
    }

    private long legacySettledWithHash(Harness harness) {
        return jdbc.sql("""
            SELECT count(*) FROM alighting_forecast_evaluation_result
            WHERE route_version_id IN (:versions) AND scoring_state = 'SETTLED' AND parameter_hash IS NOT NULL
            """).param("versions", harness.versions()).query(Long.class).single();
    }

    /** 입력 확정의 새 트랜잭션과 옛 정산의 트랜잭션을 시험 트랜잭션에 합류시킨다. 같은 SQL을 그대로 쓴다. */
    private static PlatformTransactionManager joining(PlatformTransactionManager delegate) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                DefaultTransactionDefinition joined = new DefaultTransactionDefinition(definition);
                joined.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                joined.setReadOnly(false);
                joined.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
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

    private static NumericTreeEnsemble stumps(
        final int features,
        NumericTreeEnsemble.Kind kind,
        double[] baseline,
        Random random
    ) {
        double[] thresholds = {0.0, 0.5, 1, 2, 5, 10, 30};
        List<List<List<NumericTreeEnsemble.Node>>> iterations = new ArrayList<>();
        for (int iteration = 0; iteration < 24; iteration++) {
            List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
            for (int tree = 0; tree < baseline.length; tree++) {
                trees.add(List.of(
                    new NumericTreeEnsemble.Node(random.nextInt(features),
                        thresholds[random.nextInt(thresholds.length)], random.nextBoolean(), 1, 2, 0, false),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true)));
            }
            iterations.add(trees);
        }
        return new NumericTreeEnsemble(features, kind, baseline, iterations);
    }

    /** 정류장마다 옛 정산이 읽는 근거(실정류장, 회차, 휴일, 시간 칸 계수, 정류장 계수)를 만든다. */
    private static Map<Integer, String> evidence(AlightingAwareRouteStatistics statistics, final double probability) {
        String parameters = "\"p\":%s,\"lam\":[1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9],\"kappa\":1,\"r\":2"
            .formatted(probability);
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

    private static String digest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] bytes = new byte[32];
        ByteBuffer.wrap(bytes).putLong(first.getMostSignificantBits()).putLong(first.getLeastSignificantBits())
            .putLong(second.getMostSignificantBits()).putLong(second.getLeastSignificantBits());
        return HexFormat.of().formatHex(bytes);
    }

    /** 흐름 하나와 옛 경로·그림자 예보·그림자 채점을 묶는다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final MutableClock clock = new MutableClock();
        private final ForecastBatchWriter writer;
        private final ShadowForecastService shadow;
        private final ShadowScoringService scoring;
        private final SimpleMeterRegistry scoringMeters = new SimpleMeterRegistry();
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final AlightingAwareSeatForecastModel model;
        private final AlightingAwareSeatForecastModel nextModel;
        private RuntimeSnapshot runtime;
        private long rejectBatch = -1;
        private long currentBatch = -1;

        private Harness(final long seed) {
            stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofMinutes(90), Duration.ofSeconds(30),
                null, seed, MODEL_ROUTE);
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
            Random random = new Random(seed);
            model = model(random, 0.12);
            nextModel = model(random, 0.3);
            runtime = activate(model);
            JdbcAlightingAwareFeatureQuery query = new JdbcAlightingAwareFeatureQuery(jdbc, cache, origins,
                MAXIMUM_ROWS, false, checkpoint.toString());
            writer = new ForecastBatchWriter(committer, clock, routeWork);
            writer.setAlightingAwareFeatures(query);
            writer.setConfirmedInput(new ConfirmedAlightingAwareInput(jdbc, joining(transactions)));
            writer.setCompactSettlementWrite(true);
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            ScoringStage scoringStage = new ScoringStage(new JdbcSettlementContextProvider(jdbc),
                new JdbcRouteStructureProvider(jdbc), new ConfirmedPassLabelCalculator(), clock);
            scoring = new ShadowScoringService(task -> {
                if (rejectBatch >= 0 && currentBatch == rejectBatch) {
                    return false;
                }
                task.run();
                return true;
            }, scoringStage, new ScoringReplay(records, passes),
                new ScoringWriter(labels, new TransactionTemplate(transactions)), records,
                ScoringReplay.DEFAULT_FORECAST_WINDOW, clock, scoringMeters);
            shadow = new ShadowForecastService(ShadowForecastTask::run, stage,
                new RouteStateReplayer(source, stage, Duration.ofHours(6)), source, records, passes,
                new TransactionTemplate(transactions), List.of(scoring), new RouteStateRegistry(), false,
                Clock.systemUTC(), new SimpleMeterRegistry());
            writer.setLegacyForecastListener(shadow);
            shadow.states().put(model.statistics(stopsByVersion.values().iterator().next()),
                RouteForecastState.fromVerifiedOrigin(stream.route()));
        }

        private List<Long> versions() {
            return List.copyOf(stream.stopsByVersion().keySet());
        }

        private void processAll(final long switchAt, final long rejectAt) {
            rejectBatch = rejectAt;
            for (final long batchId : stream.batches()) {
                Meta batch = meta(batchId);
                if (!"LIVE".equals(batch.origin()) || batch.outcome().startsWith("FAILED")) {
                    continue;
                }
                if (batchId == switchAt) {
                    runtime = activate(nextModel);
                }
                currentBatch = batchId;
                clock.at = intendedConfirmation.get(batchId);
                PendingForecastBatch pending = new PendingForecastBatch(batchId, batch.version(), stream.routeId(),
                    batch.receivedAt());
                try {
                    writer.writeForecastsOf(pending, stopsByVersion.get(batch.version()), runtime);
                } catch (RuntimeException rejectedByLegacy) {
                    // 옛 발행이 거부한 배치는 시나리오에서 건너뛰고, 그 배치에서는 채점 줄을 비우지 않는다.
                    continue;
                }
                scoring.flush();
            }
            currentBatch = -1;
            clock.at = clock.at.plus(Duration.ofHours(3));
            scoring.tick();
            scoring.flush();
        }

        private JdbcScoringComparison.Report settleAndCompare() {
            jdbc.sql("DELETE FROM alighting_forecast_evaluation_work WHERE route_version_id NOT IN (:versions)")
                .param("versions", versions()).update();
            jdbc.sql("DELETE FROM alighting_settlement_batch_work WHERE route_id <> ?")
                .param(stream.routeId()).update();
            jdbc.sql("SET LOCAL enable_nestloop = off").update();
            Clock settledAt = Clock.fixed(clock.at, ZoneOffset.UTC);
            JdbcAlightingAwareForecastEvaluation legacy = new JdbcAlightingAwareForecastEvaluation(jdbc, routeWork,
                settledAt, joining(transactions), 50_000, 150_000, 3000, 500, 1000, 3600);
            for (int round = 0; round < 200 && legacy.advance(); round++) {
                assertThat(round).as("옛 정산이 끝나지 않는다").isLessThan(199);
            }
            return new JdbcScoringComparison(jdbc, settledAt, Duration.ZERO, Duration.ofDays(1), 100_000, null)
                .compare();
        }

        private Meta meta(final long batchId) {
            return jdbc.sql("""
                SELECT route_version_id,response_received_at,ingestion_origin,outcome FROM observation_batch WHERE id=?
                """).param(batchId).query((row, index) -> new Meta(row.getLong(1),
                    row.getObject(2, OffsetDateTime.class).toInstant(), row.getString(3), row.getString(4)))
                .single();
        }

        private AlightingAwareSeatForecastModel model(Random random, final double probability) {
            AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
                stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
                stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
                stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
            Map<String, ExtendedAlightingPredictor> extension = Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
                stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
                stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)));
            return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
                Map.of(MODEL_ROUTE, evidence(stream.statistics(), probability)), digest(), extension);
        }

        private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
            String digest = selected.bundleDigest();
            ModelRelease release = ModelRelease.alighting("scoring-" + digest.substring(0, 8), digest,
                EVENING.minusSeconds(86400).toString(), new SupportedForecastScope(List.of(MODEL_ROUTE)), selected);
            LoadedModelRegistry registry = new LoadedModelRegistry();
            new BundleActivation(deployments, registry).activate(() -> release);
            return new ActiveForecastRuntimeResolver(deployments, registry).resolveActive().orElseThrow();
        }
    }

    private record Meta(
        long version,
        Instant receivedAt,
        String origin,
        String outcome
    ) {
    }

    /** 옛 경로의 착수 시각과 채점의 되감기 기준 시각을 배치마다 정한다. */
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
}
