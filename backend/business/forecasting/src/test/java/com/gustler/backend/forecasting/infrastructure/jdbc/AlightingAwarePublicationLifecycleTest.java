package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareEvaluationArchiveService;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 관측/입력·트리를 사용해 실제 PostgreSQL 발행·정산·복원과 배포 전환을 연결한다. */
@ForecastingIntegrationTest
class AlightingAwarePublicationLifecycleTest {
    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final String VERSION = "alighting-capacity-snapshot-v2";
    private static final String AFTER = "AFTER_ALIGHTING_ESTIMATE";
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RouteWorkAccess quality;
    @Autowired
    PlatformTransactionManager transactions;
    private TransactionTemplate tx;
    private long route;
    private long version;
    @Autowired
    ForecastBatchCommitter committer;
    @Autowired
    ModelDeploymentRepository deployments;
    private final List<Long> deploymentIds = new ArrayList<>();
    private Long originalActive;
    private long source;
    private JdbcAlightingAwareForecastEvaluation service;
    private final String snapshot = """
        {"realStopOrders":[1,2,3],"alighting":{"stops":[{"stop":2,"band":"morning",
        "p":0.12,"lam":[1.5,1.5,1.5,1.5,1.5,1.5,1.5,1.5],"kappa":1,"r":2}]}}
        """;

    @Autowired
    ConfirmedAlightingAwareInput confirmedInput;
    @Autowired
    CollectionInputs collectionInputs;

    @BeforeEach
    void 정산_자료를_준비한다() {
        tx = new TransactionTemplate(transactions);
        service = new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions);
        tx.executeWithoutResult(status -> {
            String id = UUID.randomUUID().toString().substring(0, 24);
            route = jdbc.sql("""
                INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                VALUES(:id,'GBIS',:id,'3330','A','B') RETURNING id
                """).param("id", id).query(Long.class).single();
            version = jdbc
                .sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id")
                .params(route, "0".repeat(64), offset(START)).query(Long.class).single();
            for (int stop = 1; stop <= 3; stop++) {
                jdbc.sql(
                    "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,?,?,?,?,?)")
                    .params(version, stop, "s" + stop, "정류장" + stop, "UP", stop < 3).update();
            }
            observation("previous", -120, 1, 5);
            observation("previous", -100, 2, 0);
            observation("previous", -80, 3, 0);
            source = observation("current", 0, 1, 40);
            observation("current", 20, 1, 2);
            observation("current", 40, 2, 0);
            observation("current", 60, 2, 0);
            observation("current", 80, 3, 2);
        });
        originalActive = deployments.findActive().map(ActiveModelDeployment::id).orElse(null);
    }

    @AfterEach
    void 준비한_자료를_정리한다() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("alighting_evaluation_archive", "alighting_forecast_evaluation_result",
                "alighting_forecast_evaluation_work",
                "forecast_evaluation_work", "seat_forecast", "forecast_publication")) {
                jdbc.sql("DELETE FROM " + table + " WHERE route_version_id=?").param(version).update();
            }
            jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM observation_batch WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
            jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
            for (final long id : deploymentIds.reversed()) {
                jdbc.sql("DELETE FROM model_deployment WHERE id=?").param(id).update();
            }
            if (originalActive != null) {
                jdbc.sql("UPDATE model_deployment SET state='ACTIVE',retired_at=NULL WHERE id=?").param(originalActive)
                    .update();
            }
        });
    }

    @Test
    void 새_입력_확정_후_기존_발행_경로도_확인_시각을_덮어쓰지_않는다() {
        // given
        final long batch = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
            .param(source).query(Long.class).single();
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=NULL WHERE id=?")
            .param(batch).update();
        Instant first = START.plusSeconds(1);

        // when
        confirmedInput.confirm(batch, first);
        confirmedInput.confirm(batch, START.plusSeconds(30));
        tx.executeWithoutResult(status -> collectionInputs.confirmInput(batch, START.plusSeconds(60)));

        // then
        assertThat(jdbc.sql("SELECT input_confirmed_at FROM observation_batch WHERE id=?")
            .param(batch).query(OffsetDateTime.class).single().toInstant()).isEqualTo(first);
    }

    @Test
    void 입력_확정_기한이_지난_배치는_제외하고_새_배치는_계산_대상에_포함한다() {
        final long expired = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
            .param(source).query(Long.class).single();
        final long next = jdbc.sql("SELECT id FROM observation_batch WHERE route_version_id=? AND response_received_at=?")
            .params(version, START.plusSeconds(20).atOffset(ZoneOffset.UTC)).query(Long.class).single();
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=NULL WHERE id IN (?,?)")
            .params(expired, next).update();
        Instant confirmedAt = START.plusSeconds(91);

        assertThat(confirmedInput.confirm(expired, confirmedAt)).isFalse();
        assertThat(confirmedInput.confirm(next, confirmedAt)).isTrue();
        assertThat(confirmedInput.confirm(expired, START.plusSeconds(95))).isFalse();
    }

    @Test
    void 예보에_만석_확률을_저장하고_하차_후_잔여석만_정산한다() {
        AlightingAwareRouteStatistics stats = statistics();
        AlightingAwareSeatForecastModel model = new AlightingAwareSeatForecastModel(new AlightingAwareSeatPredictor(
            constant(88, NumericTreeEnsemble.Kind.REGRESSION, 10),
            constant(88, NumericTreeEnsemble.Kind.MULTICLASS, 0, 0, 0),
            constant(80, NumericTreeEnsemble.Kind.BINARY, Math.log(4)), 1.3),
            Map.of("3330", stats), Map.of("3330", Map.of(2, snapshot)), "b".repeat(64));
        ModelRelease release = ModelRelease.alighting("alighting-lifecycle", "b".repeat(64), START.minusSeconds(86400).toString(),
            new SupportedForecastScope(List.of("3330")), model);
        LoadedModelRegistry registry = new LoadedModelRegistry();
        BundleActivation activation = new BundleActivation(deployments, registry);
        final long deployed = tx.execute(status -> activation.activate(() -> release));
        deploymentIds.add(deployed);
        ActiveForecastRuntimeResolver resolver = new ActiveForecastRuntimeResolver(deployments, registry);
        RuntimeSnapshot selected = resolver.resolveActive().orElseThrow();
        final long batchId = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
            .param(source).query(Long.class).single();
        RouteStops stops = new RouteStops(version, "204000057", List.of(
            new RouteStop(version, 1, "s1", true, RouteDirection.UP),
            new RouteStop(version, 2, "s2", true, RouteDirection.UP),
            new RouteStop(version, 3, "s3", false, RouteDirection.UP)));
        AlightingAwareFeatureQuery featureQuery = mock(AlightingAwareFeatureQuery.class);
        AlightingAwareFeatureSnapshot featureSnapshot = mock(AlightingAwareFeatureSnapshot.class);
        when(featureQuery.read(batchId, stops, stats)).thenReturn(Map.of(source, featureSnapshot));
        when(featureSnapshot.vehicleObservationId()).thenReturn(source);
        when(featureSnapshot.capacity()).thenReturn(40);
        when(featureSnapshot.observation()).thenReturn(new ObservedVehicle("current", version, 1, START, 40, null));
        when(featureSnapshot.forTarget(2)).thenReturn(new AlightingAwareFeatureVector(new double[88], new double[80]));
        ForecastBatchWriter writer = new ForecastBatchWriter( committer,
            Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC), quality);
        writer.setAlightingAwareFeatures(featureQuery);
        PendingForecastBatch batch = new PendingForecastBatch(batchId, version, route, START);

        assertThat(writer.writeForecastsOf(batch, stops, selected)).isEqualTo(1);
        assertThat(writer.writeForecastsOf(batch, stops, selected)).isEqualTo(1);
        assertThat(count("seat_forecast")).isEqualTo(1);
        assertThat(count("forecast_publication")).isEqualTo(1);
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(jdbc.sql("SELECT input_confirmed_at IS NOT NULL FROM observation_batch WHERE id=?")
            .param(batchId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT feature_snapshot::text FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).contains("seat88", "probability80", "asOf");
        final double prediction = jdbc.sql("SELECT expected_seats FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Double.class).single();
        assertThat(prediction).isCloseTo((10 + 1.3) / 3, within(1e-12));

        assertThat(jdbc.sql("SELECT seat_full_chance FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Double.class).single()).isEqualTo(0.8);

        service.settle();
        service.settle();
        final double after = label(AFTER);
        assertThat(after).isGreaterThan(2).isNotEqualTo(Math.rint(after));
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();

        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(
            new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), objects, true, 500);
        archive.advance();
        assertThat(count("alighting_forecast_evaluation_result")).isZero();
        assertThat(count("alighting_forecast_evaluation_metric")).isOne();
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isEqualTo(1);
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isZero();
        assertThat(label(AFTER)).isEqualTo(after);
        assertThat(count("alighting_forecast_evaluation_metric")).isOne();

        ModelRelease legacy = new ModelRelease("legacy-lifecycle", "legacy-v1", "a".repeat(64), "legacy-v1",
            START.minusSeconds(86400).toString(), new SupportedForecastScope(List.of("3330")),
            mock(AlightingAwareSeatForecastModel.class));
        final long rolledBack = tx.execute(status -> activation.activate(() -> legacy));
        deploymentIds.add(rolledBack);
        assertThat(resolver.resolveActive().orElseThrow().model()).isInstanceOf(AlightingAwareSeatForecastModel.class);
        assertThat(selected.model()).isSameAs(model);
        assertThat(jdbc.sql("SELECT model_deployment_id FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Long.class).single()).isEqualTo(deployed);
        assertThat(jdbc.sql("SELECT expected_seats FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Double.class).single()).isEqualTo(prediction);
        assertThat(count("alighting_forecast_evaluation_metric")).isOne();
    }

    @Test
    void 휴일_예보를_한번_발행하고_휴일_파라미터로_정산한다() {
        // given
        jdbc.sql("""
            UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at
            WHERE route_version_id=?
            """).param(version).update();
        final long batchId = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
            .param(source).query(Long.class).single();
        AlightingAwareRouteStatistics original = statistics();
        AlightingAwareRouteStatistics stats = new AlightingAwareRouteStatistics("3330", original.stops(),
            original.minutesPerStop(), original.tables(), Map.of(), Set.of(LocalDate.parse("2026-01-05")), true);
        String extendedEvidence = """
            {"turn":4,"realStopOrders":[1,2,3],"holidays":["2026-01-05"],
             "extendedSettlement":{"holiday-day":{"p":0.12,"lam":[1.5,1.5,1.5,1.5,1.5,1.5,1.5,1.5],"kappa":1,"r":2}}}
            """;
        ExtendedAlightingPredictor extension = new ExtendedAlightingPredictor(
            constant(6, NumericTreeEnsemble.Kind.REGRESSION, 7),
            constant(6, NumericTreeEnsemble.Kind.BINARY, Math.log(4)));
        AlightingAwareSeatForecastModel model = new AlightingAwareSeatForecastModel(new AlightingAwareSeatPredictor(
            constant(88, NumericTreeEnsemble.Kind.REGRESSION, 10),
            constant(88, NumericTreeEnsemble.Kind.MULTICLASS, 0, 0, 0),
            constant(80, NumericTreeEnsemble.Kind.BINARY, 0), 1.3),
            Map.of("3330", stats), Map.of("3330", Map.of(2, extendedEvidence)),
            "b".repeat(64), Map.of("3330", extension));
        ModelRelease release = ModelRelease.alighting("all-hours-lifecycle", "b".repeat(64),
            START.minusSeconds(86400).toString(), new SupportedForecastScope(List.of("3330")), model);
        LoadedModelRegistry registry = new LoadedModelRegistry();
        BundleActivation activation = new BundleActivation(deployments, registry);
        final long deployed = tx.execute(status -> activation.activate(() -> release));
        deploymentIds.add(deployed);
        RuntimeSnapshot runtime = new ActiveForecastRuntimeResolver(deployments, registry).resolveActive().orElseThrow();
        RouteStops stops = new RouteStops(version, "204000057", List.of(
            new RouteStop(version, 1, "s1", true, RouteDirection.UP),
            new RouteStop(version, 2, "s2", true, RouteDirection.UP),
            new RouteStop(version, 3, "s3", false, RouteDirection.UP)));
        AlightingAwareFeatureQuery query = mock(AlightingAwareFeatureQuery.class);
        AlightingAwareFeatureSnapshot feature = mock(AlightingAwareFeatureSnapshot.class);
        when(query.readExtended(batchId, stops, stats)).thenReturn(Map.of(source, feature));
        when(feature.vehicleObservationId()).thenReturn(source);
        when(feature.capacity()).thenReturn(40);
        when(feature.source()).thenReturn(new AlightingAwareObservation(source, batchId, 0, START, "current",
            1, 1, 2, 40, 0, version, START, false));
        when(feature.provenance()).thenReturn(new AlightingAwareFeatureSnapshot.Provenance(source));
        when(feature.observation()).thenReturn(new ObservedVehicle("current", version, 1, START, 40, null));
        ForecastBatchWriter writer = new ForecastBatchWriter(committer, Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC), quality);
        writer.setAlightingAwareFeatures(query);
        writer.setConfirmedInput(confirmedInput);
        PendingForecastBatch batch = new PendingForecastBatch(batchId, version, route, START);

        // when
        writer.writeForecastsOf(batch, stops, runtime);
        writer.writeForecastsOf(batch, stops, runtime);
        service.settle();
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions).settle();

        // then
        assertThat(count("forecast_publication")).isOne();
        assertThat(count("seat_forecast")).isOne();
        assertThat(jdbc.sql("SELECT expected_seats FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Double.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT seat_full_chance FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(Double.class).single()).isEqualTo(0.8);
        assertThat(jdbc.sql("""
            SELECT feature_snapshot->'extendedInputs'->>1 FROM seat_forecast WHERE vehicle_observation_id=?
            """).param(source).query(Double.class).single()).isEqualTo(40);
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(jdbc.sql("SELECT scoring_state FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(label(AFTER)).isGreaterThan(2);
    }

    private AlightingAwareRouteStatistics statistics() {
        Map<String, Map<String, Double>> tables = new HashMap<String, Map<String, Double>>();
        for (String name : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(name, Map.of());
        }
        return new AlightingAwareRouteStatistics("3330", List.of(
            new AlightingAwareRouteStatistics.Stop(1, "s1", "정류장1", false, true),
            new AlightingAwareRouteStatistics.Stop(2, "s2", "정류장2", false, true),
            new AlightingAwareRouteStatistics.Stop(3, "s3", "정류장3", false, false)), 1, tables, Map.of(), Set.of());
    }

    private static NumericTreeEnsemble constant(final int count, NumericTreeEnsemble.Kind kind, double... baseline) {
        List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
        for (int i = 0; i < baseline.length; i++) {
            trees.add(List.of(new NumericTreeEnsemble.Node(0, 0, false, 0, 0, 0, true)));
        }
        return new NumericTreeEnsemble(count, kind, baseline, List.of(trees));
    }

    private long observation(
        String vehicle,
        final int seconds,
        final int stop,
        final int seats
    ) {
        OffsetDateTime time = offset(START.plusSeconds(seconds));
        final long batch = jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,response_received_at,
                        outcome,normalization_version,collection_strategy_version,stored_rows)
                    VALUES(?,?,1,?,?,?,'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1',1) RETURNING id
                    """)
            .params(version, time, UUID.randomUUID().toString(), time, time).query(Long.class).single();
        final long id = jdbc
            .sql(
                """
                    INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,
                        passed_stop_order,running_state,remaining_seats) VALUES(?,?,0,?,?,?,?,2,?) RETURNING id
                    """)
            .params(batch, version, vehicle, stop, "s" + stop, stop, seats).query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, id);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE route_version_id=?").param(version)
            .query(Integer.class).single();
    }

    private double label(String target) {
        return jdbc.sql(
            "SELECT label_value FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, target).query(Double.class).single();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static final class MemoryObjects implements EvaluationArchiveObjects {
        private final Map<String, byte[]> bytes = new HashMap<>();

        public void putIfAbsent(
            String bucket,
            String key,
            Path source,
            String checksum
        ) {
            try {
                bytes.putIfAbsent(key, Files.readAllBytes(source));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        public void download(
            String bucket,
            String key,
            Path destination,
            final long limit
        ) {
            try {
                Files.write(destination, bytes.get(key));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
