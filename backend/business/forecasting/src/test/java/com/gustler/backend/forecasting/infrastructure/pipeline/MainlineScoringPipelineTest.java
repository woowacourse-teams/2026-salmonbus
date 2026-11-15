package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineBatchProcessor;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateRegistry;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 예보 경로를 본선으로 돌리고({@code forecast.pipeline=v2}) 그 사건으로 새 채점을 돌린다({@code forecast.scoring=v2}).
 * 채점 결과가 저장되는지, 그리고 같은 흐름을 옛 경로(옛 발행 + 옛 정산)로 따로 처리한 결과와 예측마다 같은지 본다.
 *
 * <p>본선은 옛 발행을 쓰지 않으므로 새 채점이 읽는 모델 배포의 정산 근거를 본선이 직접 적는다. 옛 발행은 본선을 다 돌린 뒤 같은
 * 착수 시각으로 처리하며, 같은 정산 근거를 다시 적으려 해도 값이 같아 통과해야 한다. 노선별 실행 줄과 채점 줄은 넣는 즉시 같은
 * 스레드에서 실행한다.
 */
@ForecastingIntegrationTest
@Transactional
class MainlineScoringPipelineTest {
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
    void 본선의_사건이_새_채점으로_가서_정답이_저장되고_옛_경로로_처리한_정산과_예측마다_같다() {
        // given
        Harness harness = new Harness(20261210L);

        // when
        harness.mainlineAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT count(*) FROM pass_label WHERE route_id=? AND state='SETTLED'")
                .param(harness.stream.routeId()).query(Long.class).single()).isPositive();
            softly.assertThat(jdbc.sql("SELECT count(*) FROM seat_forecast WHERE route_version_id IN (:versions)")
                .param("versions", harness.versions()).query(Long.class).single()).isZero();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM model_deployment WHERE id=? AND jsonb_exists(settlement_context, ?)
                """).params(harness.runtime.deploymentId(), Long.toString(harness.versions().getFirst()))
                .query(Long.class).single()).isOne();
        });

        // when
        JdbcScoringComparison.Report report = harness.legacyAndCompare();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.unexplained())
                .as("설명되지 않는 차이: %s", report.unexplained().stream().limit(10).map(String::valueOf)
                    .collect(Collectors.joining("\n")))
                .isEmpty();
            softly.assertThat(report.count(JdbcScoringComparison.Result.LEGACY_ONLY)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.NEW_ONLY)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.PENDING)).isZero();
            softly.assertThat(report.count(JdbcScoringComparison.Result.SAME)).isGreaterThan(1_000);
        });
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

    /** 흐름 하나와 본선·새 채점·옛 경로를 묶는다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final Map<Long, Meta> meta = new HashMap<>();
        private final PipelineTestSupport.MutableClock clock = new PipelineTestSupport.MutableClock();
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final AlightingAwareSeatForecastModel model;
        private final RuntimeSnapshot runtime;
        private final ShadowScoringService scoring;
        private final ForecastPipelineService mainline;
        private final String origins;

        private Harness(final long seed) {
            stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofMinutes(90), Duration.ofSeconds(30),
                null, seed, MODEL_ROUTE);
            origins = ObservationStreamFixture.origins(jdbc, stream);
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
            ScoringStage scoringStage = new ScoringStage(new JdbcSettlementContextProvider(jdbc),
                new JdbcRouteStructureProvider(jdbc), new ConfirmedPassLabelCalculator(), clock);
            scoring = new ShadowScoringService(task -> {
                task.run();
                return true;
            }, scoringStage, new ScoringReplay(records, passes),
                new ScoringWriter(labels, new TransactionTemplate(transactions)), records,
                ScoringReplay.DEFAULT_FORECAST_WINDOW, clock, new SimpleMeterRegistry());
            JdbcReplaySource source = new JdbcReplaySource(jdbc, MAXIMUM_ROWS, JdbcReplaySource.Axis.RECORDED_RUN);
            ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
            PipelineBatchProcessor processor = new PipelineBatchProcessor("pipeline_forecast", stage,
                new RouteStateReplayer(source, stage, Duration.ofHours(6)), records, passes,
                new TransactionTemplate(transactions), List.of(scoring), new RouteStateRegistry(), false);
            mainline = new ForecastPipelineService((key, task) -> {
                task.run();
                return true;
            }, processor, source, new JdbcPendingPipelineBatches(jdbc), () -> Optional.of(runtime), this::versions,
                stopsByVersion::get, new JdbcSettlementContextWriter(jdbc),
                new ForecastPolicy(Duration.ofMinutes(10), 20, 3000, 400), clock, new SimpleMeterRegistry());
            clock.at = EVENING.plus(Duration.ofDays(2));
            mainline.pickUp();
            mainline.states().put(model.statistics(stopsByVersion.values().iterator().next()),
                RouteForecastState.fromVerifiedOrigin(stream.route()));
        }

        private List<Long> versions() {
            return List.copyOf(stream.stopsByVersion().keySet());
        }

        private void mainlineAll() {
            for (final long batchId : stream.batches()) {
                Meta batch = meta.get(batchId);
                if (batch.outcome().startsWith("FAILED")) {
                    continue;
                }
                clock.at = intendedConfirmation.get(batchId);
                mainline.batchStored(batchId, batch.version());
                scoring.flush();
            }
            clock.at = clock.at.plus(Duration.ofHours(3));
            scoring.tick();
            scoring.flush();
        }

        /** 같은 흐름을 옛 발행으로 처리하고 옛 정산을 돌린 뒤 새 채점 결과와 대조한다. */
        private JdbcScoringComparison.Report legacyAndCompare() {
            Instant settledAt = clock.at;
            ForecastBatchWriter writer = new ForecastBatchWriter(committer, clock, routeWork);
            writer.setAlightingAwareFeatures(new JdbcAlightingAwareFeatureQuery(jdbc, cache, origins, MAXIMUM_ROWS,
                false, checkpoint.toString()));
            writer.setConfirmedInput(new ConfirmedAlightingAwareInput(jdbc, PipelineTestSupport.joining(transactions)));
            writer.setCompactSettlementWrite(true);
            for (final long batchId : stream.batches()) {
                Meta batch = meta.get(batchId);
                if (!"LIVE".equals(batch.origin()) || batch.outcome().startsWith("FAILED")) {
                    continue;
                }
                clock.at = intendedConfirmation.get(batchId);
                writer.writeForecastsOf(new PendingForecastBatch(batchId, batch.version(), stream.routeId(),
                    batch.receivedAt()), stopsByVersion.get(batch.version()), runtime);
            }
            jdbc.sql("DELETE FROM alighting_forecast_evaluation_work WHERE route_version_id NOT IN (:versions)")
                .param("versions", versions()).update();
            jdbc.sql("DELETE FROM alighting_settlement_batch_work WHERE route_id <> ?")
                .param(stream.routeId()).update();
            jdbc.sql("SET LOCAL enable_nestloop = off").update();
            Clock fixed = Clock.fixed(settledAt, ZoneOffset.UTC);
            JdbcAlightingAwareForecastEvaluation legacy = new JdbcAlightingAwareForecastEvaluation(jdbc, routeWork,
                fixed, PipelineTestSupport.joining(transactions), 50_000, 150_000, 3000, 500, 1000, 3600);
            for (int round = 0; round < 200 && legacy.advance(); round++) {
                assertThat(round).as("옛 정산이 끝나지 않는다").isLessThan(199);
            }
            return new JdbcScoringComparison(jdbc, fixed, Duration.ZERO, Duration.ofDays(1), 100_000, null).compare();
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
                Map.of(MODEL_ROUTE, evidence(stream.statistics(), 0.12)), PipelineTestSupport.digest(), extension);
        }

        private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
            String digest = selected.bundleDigest();
            ModelRelease release = ModelRelease.alighting("mainline-scoring-" + digest.substring(0, 8), digest,
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
}
