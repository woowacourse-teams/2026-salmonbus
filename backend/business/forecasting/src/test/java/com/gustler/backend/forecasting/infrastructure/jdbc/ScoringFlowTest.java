package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.RouteForecastState;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
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
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 경로가 낸 사건으로 채점을 돌려 저장된 결과를 실제 DB로 본다. 기대값은 기준 구현(dev {@code e98ae3ea}의 발행·정산)이 같은
 * 흐름에 낸 예측별 결과다({@link ScoringBaseline}).
 *
 * <p>채점이 읽는 모델 배포의 정산 근거는 예보 경로가 적는다. 노선별 실행 줄과 채점 줄은 넣는 즉시 같은 스레드에서 실행한다.
 * 흐름을 다 넣은 뒤 마지막 배치 3시간 뒤의 시계로 주기 작업을 돌려 모든 예측을 마감한다.
 */
@ForecastingIntegrationTest
@Transactional
class ScoringFlowTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T09:30:00Z");
    private static final String SOURCE_ROUTE_ID = "234001739";
    private static final String MODEL_ROUTE = "6801";
    private static final long NEVER = -1;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    JdbcForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;
    private VehicleCapacityCache cache;

    @BeforeEach
    void 차량_정원_캐시를_준비한다() {
        cache = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
    }

    @Test
    void 예보_사건이_채점으로_가서_정답이_저장되고_예측마다_기준_기록과_같다() {
        // given
        Harness harness = new Harness(20261210L);
        ScoringBaseline baseline = ScoringBaseline.read("scoring/basic-20261210");

        // when
        harness.processAll(NEVER, NEVER);

        // then
        ScoringBaseline.Comparison comparison = baseline.compare(jdbc, harness.keys, harness.stream.routeId());
        assertSame(comparison);
        assertSoftly(softly -> {
            softly.assertThat(comparison.same()).isGreaterThan(1_000);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM pass_label WHERE route_id=? AND state='SETTLED'")
                .param(harness.stream.routeId()).query(Long.class).single()).isPositive();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM model_deployment WHERE id=? AND jsonb_exists(settlement_context, ?)
                """).params(harness.runtime.deploymentId(), Long.toString(harness.versions().getFirst()))
                .query(Long.class).single()).isOne();
            softly.assertThat(states(harness)).containsKeys("SETTLED", "UNSUPPORTED", "LOST")
                .doesNotContainKey("PENDING");
        });
    }

    @Test
    void 모델_배포가_바뀌는_순간의_통과는_배포마다_정답을_따로_내고_예측마다_기준_기록과_같다() {
        // given
        Harness harness = new Harness(20261121L);
        ScoringBaseline baseline = ScoringBaseline.read("scoring/deployment-switch-20261121");
        final long switchAt = harness.stream.batches().get(harness.stream.batches().size() / 2);

        // when
        harness.processAll(switchAt, NEVER);

        // then
        assertSame(baseline.compare(jdbc, harness.keys, harness.stream.routeId()));
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
    void 채점_줄이_넘쳐_사건을_버려도_저장된_기록을_되감아_예측마다_기준_기록과_같다() {
        // given
        Harness harness = new Harness(20261122L);
        ScoringBaseline baseline = ScoringBaseline.read("scoring/dropped-20261122");
        final long dropAt = harness.stream.batches().get(harness.stream.batches().size() / 3);

        // when
        harness.processAll(NEVER, dropAt);

        // then
        assertSame(baseline.compare(jdbc, harness.keys, harness.stream.routeId()));
        assertSoftly(softly -> {
            softly.assertThat(harness.scoringMeters.find("salmonbus.scoring.dropped").counter().count())
                .isPositive();
            softly.assertThat(harness.scoringMeters.find("salmonbus.scoring.replays").tag("reason", "dropped")
                .tag("result", "succeeded").counter().count()).isPositive();
        });
    }

    private static void assertSame(ScoringBaseline.Comparison comparison) {
        assertSoftly(softly -> {
            softly.assertThat(comparison.differences())
                .as("기대값과 다른 예측: %s", comparison.differences().stream().limit(10)
                    .collect(Collectors.joining("\n")))
                .isEmpty();
            softly.assertThat(comparison.baselineOnly()).isEmpty();
            softly.assertThat(comparison.newOnly()).isEmpty();
            softly.assertThat(comparison.pending()).isZero();
            softly.assertThat(comparison.same()).isPositive();
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

    /** 정류장마다 정답 계산이 읽는 근거(실정류장, 회차, 휴일, 시간 칸 계수, 정류장 계수)를 만든다. */
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

    /** 흐름 하나와 예보 경로·채점을 묶는다. 모델 배포는 둘을 만들어 두고 처음에는 앞의 것을 쓴다. */
    private final class Harness {
        private final ObservationStreamFixture.Stream stream;
        private final StreamKeys keys;
        private final Map<Long, Instant> intendedConfirmation = new HashMap<>();
        private final Map<Long, Meta> meta = new HashMap<>();
        private final ForecastTestSupport.MutableClock clock = new ForecastTestSupport.MutableClock();
        private final Map<Long, RouteStops> stopsByVersion = new HashMap<>();
        private final SimpleMeterRegistry scoringMeters = new SimpleMeterRegistry();
        private final CommittedBatches committed = new CommittedBatches(new JdbcPendingBatches(jdbc));
        private final AlightingAwareSeatForecastModel model;
        private final AlightingAwareSeatForecastModel nextModel;
        private final ScoringService scoring;
        private final RunForecastsService service;
        private RuntimeSnapshot runtime;
        private long rejectBatch = NEVER;
        private long currentBatch = NEVER;

        private Harness(final long seed) {
            stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofMinutes(90), Duration.ofSeconds(30),
                null, seed, MODEL_ROUTE);
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
            Random random = new Random(seed);
            model = model(random, 0.12);
            nextModel = model(random, 0.3);
            runtime = activate(model);
            scoring = new ForecastAssembly(jdbc, transactions, clock, scoringMeters).scoring(task -> {
                if (rejectBatch != NEVER && currentBatch == rejectBatch) {
                    return false;
                }
                task.run();
                return true;
            }, records, records, passes, labels);
            JdbcReplaySource source = new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS);
            ForecastAssembly.Sources sources = new ForecastAssembly.Sources(source, source, committed,
                () -> Optional.of(runtime), this::versions, stopsByVersion::get);
            ForecastAssembly.Settings settings = new ForecastAssembly.Settings(
                new ForecastPolicy(Duration.ofMinutes(10), 20), Duration.ofHours(6), Duration.ofHours(6), false);
            service = new ForecastAssembly(jdbc, transactions, clock, new SimpleMeterRegistry())
                .forecast((key, task) -> {
                    task.run();
                    return true;
                }, scoring, sources, cache, records, passes, settings);
            clock.at = EVENING.plus(Duration.ofDays(2));
            service.pickUp();
            service.states().put(model.statistics(stopsByVersion.values().iterator().next()),
                RouteForecastState.fromVerifiedOrigin(stream.route()));
        }

        private List<Long> versions() {
            return List.copyOf(stream.stopsByVersion().keySet());
        }

        /**
         * 흐름을 처리 순서로 넣고 배치마다 채점 줄을 비운 뒤, 마지막 배치 3시간 뒤의 시계로 주기 작업을 돌린다.
         *
         * @param switchAt 이 배치부터 다음 모델 배포를 쓴다. {@link #NEVER}면 바꾸지 않는다
         * @param rejectAt 이 배치의 사건은 채점 줄이 받지 않는다. {@link #NEVER}면 모두 받는다
         */
        private void processAll(final long switchAt, final long rejectAt) {
            rejectBatch = rejectAt;
            for (final long batchId : stream.batches()) {
                Meta batch = meta.get(batchId);
                if (batch.outcome().startsWith("FAILED")) {
                    continue;
                }
                if (batchId == switchAt) {
                    runtime = activate(nextModel);
                }
                currentBatch = batchId;
                clock.at = intendedConfirmation.get(batchId);
                committed.commit(batchId);
                service.batchStored(batchId, batch.version());
                scoring.flush();
            }
            currentBatch = NEVER;
            clock.at = clock.at.plus(Duration.ofHours(3));
            scoring.tick();
            scoring.flush();
        }

        private AlightingAwareSeatForecastModel model(Random random, final double probability) {
            AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
                ForecastTestSupport.stumps(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}, random),
                ForecastTestSupport.stumps(88, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}, random),
                ForecastTestSupport.stumps(80, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random), 1.3);
            Map<String, ExtendedAlightingPredictor> extension = Map.of(MODEL_ROUTE, new ExtendedAlightingPredictor(
                ForecastTestSupport.stumps(6, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}, random),
                ForecastTestSupport.stumps(6, NumericTreeEnsemble.Kind.BINARY, new double[] {0}, random)));
            return new AlightingAwareSeatForecastModel(predictor, Map.of(MODEL_ROUTE, stream.statistics()),
                Map.of(MODEL_ROUTE, evidence(stream.statistics(), probability)), ForecastTestSupport.digest(),
                extension);
        }

        private RuntimeSnapshot activate(AlightingAwareSeatForecastModel selected) {
            String digest = selected.bundleDigest();
            ModelRelease release = ModelRelease.alighting("forecast-scoring-" + digest.substring(0, 8), digest,
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
