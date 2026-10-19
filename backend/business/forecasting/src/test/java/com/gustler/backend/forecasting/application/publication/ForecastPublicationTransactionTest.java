package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 외부 테스트 트랜잭션 없이 실제 발행의 커밋·롤백과 수집 배치 잠금을 검증한다. */
@ForecastingIntegrationTest
@Import(ForecastPublicationTransactionTest.FixedClock.class)
class ForecastPublicationTransactionTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant GENERATED_AT = OBSERVED_AT.plusSeconds(2);
    private static final Clock CLOCK = Clock.fixed(GENERATED_AT, ZoneId.of("Asia/Seoul"));
    private static final String DIGEST = "0".repeat(64);
    private static final String CALCULATION_VERSION = "CALCULATION_V1";
    private static final int ATTEMPT_NUMBER = 2;
    private static final long MISSING_OBSERVATION_ID = Long.MAX_VALUE;
    private static final SeatForecastResult RESULT =
        new SeatForecastResult(new SeatDistribution(List.of(0.4, 0.6)), 0.4);
    private static final SeatForecastResult SECOND_RESULT =
        new SeatForecastResult(new SeatDistribution(List.of(0.7, 0.3)), 0.7);

    @Autowired
    private ForecastBatchWriter writer;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private VehicleTrajectoryQuery trajectories;

    @MockitoBean
    private StopDemandStatisticsRepository statistics;

    @MockitoBean
    private SameDayFullOutcomesService outcomes;

    @Autowired
    private RouteDataQualityAccess quality;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    private ForecastRuntime forecastRuntime;

    private final List<Long> deploymentIds = new ArrayList<>();
    private long routeId;
    private long routeVersionId;
    private long batchId;
    private long observationId;
    private PendingForecastBatch batch;
    private RouteStops stops;
    private RuntimeSnapshot runtime;

    @BeforeEach
    void 발행_입력을_별도_트랜잭션에서_조회할_수_있게_저장한다() {
        when(forecastRuntime.resolveActive()).thenReturn(Optional.empty());
        final String routeKey = "publication-" + UUID.randomUUID().toString().substring(0, 12);
        routeId = jdbc.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name
                ) VALUES (?, 'GBIS', ?, '3330', '범계역', '강남역') RETURNING id
                """)
            .params(routeKey, routeKey).query(Long.class).single();
        routeVersionId = jdbc.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?) RETURNING id
                """)
            .params(routeId, DIGEST, offset(OBSERVED_AT)).query(Long.class).single();
        for (int order = 3; order <= 5; order++) {
            jdbc.sql("""
                    INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                    VALUES (?, ?, ?, ?, 'UP', true)
                    """)
                .params(routeVersionId, order, "stop-" + order, "정류장 " + order).update();
        }
        batchId = jdbc.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                    response_received_at, completed_at, outcome, normalization_version, collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'SUCCESS_ROWS', 'normalization-v1', 'collection-v1')
                RETURNING id
                """)
            .params(routeVersionId, offset(OBSERVED_AT), ATTEMPT_NUMBER, routeKey,
                offset(OBSERVED_AT), offset(OBSERVED_AT), offset(OBSERVED_AT))
            .query(Long.class).single();
        observationId = jdbc.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number, vehicle_id,
                    stop_order, stop_id, passed_stop_order, running_state, remaining_seats
                ) VALUES (?, ?, 0, 'vehicle-1', 3, 'stop-3', 3, 2, 12) RETURNING id
                """)
            .params(batchId, routeVersionId).query(Long.class).single();
        batch = new PendingForecastBatch(batchId, routeVersionId, routeId, OBSERVED_AT);
        stops = new RouteStops(routeVersionId, routeKey, List.of(
            new RouteStop(routeVersionId, 4, "stop-4", true),
            new RouteStop(routeVersionId, 5, "stop-5", true)));
        runtime = runtime("first-release", input -> RESULT);
        when(statistics.readAsOf(eq(routeVersionId), any(), eq(CALCULATION_VERSION), eq(OBSERVED_AT)))
            .thenReturn(new StopDemandStatistics(routeVersionId, ForecastTimeSlot.of(batch, CLOCK), 0, List.of()));
        when(outcomes.outcomesFor(routeId, 1L, OBSERVED_AT)).thenReturn(Map.of());
        when(trajectories.readTrajectories(batchId)).thenReturn(List.of(trajectory(observationId)));
    }

    @AfterEach
    void 이_테스트에서_저장한_자료를_정리한다() {
        jdbc.sql("DROP TRIGGER IF EXISTS publication_work_failure ON forecast_evaluation_work").update();
        jdbc.sql("DROP FUNCTION IF EXISTS publication_work_failure()").update();
        if (routeVersionId != 0) {
            jdbc.sql("DELETE FROM forecast_evaluation_work WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM forecast_evaluation_result WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM seat_forecast WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM forecast_publication WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM observation_batch WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route_version WHERE id = ?").param(routeVersionId).update();
        }
        if (routeId != 0) {
            jdbc.sql("DELETE FROM route_data_quality WHERE route_id = ?").param(routeId).update();
            jdbc.sql("DELETE FROM route WHERE id = ?").param(routeId).update();
        }
        for (long deploymentId : deploymentIds) {
            jdbc.sql("DELETE FROM model_deployment WHERE id = ?").param(deploymentId).update();
        }
    }

    @Test
    void 발행과_예측과_평가를_함께_저장하고_같은_배치를_다시_발행하면_새_계산으로_덮어쓴다() {
        // when
        writer.writeForecastsOf(batch, stops, runtime);

        // then
        StoredPublication publication = readPublication();
        List<StoredPrediction> predictions = readPredictions();
        assertThat(publication.modelDeploymentId()).isEqualTo(runtime.deploymentId());
        assertThat(publication.publishedAt()).isEqualTo(GENERATED_AT);
        assertThat(publication.predictionCount()).isEqualTo(2);
        assertThat(predictions).containsExactly(
            new StoredPrediction(publication.id(), observationId, 4, runtime.deploymentId(), 0.4, 0.6, GENERATED_AT),
            new StoredPrediction(publication.id(), observationId, 5, runtime.deploymentId(), 0.4, 0.6, GENERATED_AT));
        assertThat(readEvaluationStates()).containsExactly("PENDING", "PENDING");
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));

        // given
        RuntimeSnapshot replacement = runtime("second-release", input -> SECOND_RESULT);

        // when
        writer.writeForecastsOf(batch, stops, replacement);

        // then
        StoredPublication republished = readPublication();
        assertThat(republished.id()).isEqualTo(publication.id());
        assertThat(republished.modelDeploymentId()).isEqualTo(replacement.deploymentId());
        assertThat(republished.predictionCount()).isEqualTo(2);
        assertThat(readPredictions()).containsExactly(
            new StoredPrediction(publication.id(), observationId, 4, replacement.deploymentId(), 0.7, 0.3, GENERATED_AT),
            new StoredPrediction(publication.id(), observationId, 5, replacement.deploymentId(), 0.7, 0.3, GENERATED_AT));
        assertThat(readEvaluationStates()).containsExactly("PENDING", "PENDING");
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
    }

    @Test
    void 일부_예측을_저장한_뒤_실패하면_발행과_평가와_입력_확정도_롤백한다() {
        // given
        when(trajectories.readTrajectories(batchId))
            .thenReturn(List.of(trajectory(observationId), trajectory(MISSING_OBSERVATION_ID)));

        // when & then
        assertThatThrownBy(() -> writer.writeForecastsOf(batch, stops, runtime))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
    }

    @Test
    void 예보를_계산하는_동안_같은_노선의_품질_행을_잠그는_트랜잭션을_완료할_수_있다() {
        // given
        RuntimeSnapshot checking = runtime("unlocked-calculation", input -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            new TransactionTemplate(transactions).executeWithoutResult(status -> quality.lock(routeVersionId));
            return RESULT;
        });

        // when
        int saved = writer.writeForecastsOf(batch, stops, checking);

        // then
        assertThat(saved).isEqualTo(2);
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
    }

    @Test
    void 다른_트랜잭션이_품질_행을_잠그고_있어도_기다리지_않고_예보를_저장한다() throws Exception {
        // given
        new TransactionTemplate(transactions).executeWithoutResult(status -> quality.lock(routeVersionId));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(routeVersionId);
            locked.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            // when
            int saved = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> writer.writeForecastsOf(batch, stops, runtime));

            // then
            assertThat(saved).isEqualTo(2);
            assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void 품질_버전을_올리고_커밋하지_않은_트랜잭션이_있으면_끝날_때까지_기다린_뒤_저장하지_않는다() throws Exception {
        // given
        new TransactionTemplate(transactions).executeWithoutResult(status -> quality.lock(routeVersionId));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread changer = Thread.ofPlatform().start(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(routeVersionId);
            jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                .param(routeId).update();
            changed.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        assertThat(changed.await(2, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableFuture<Integer> saving = CompletableFuture.supplyAsync(() -> writer.writeForecastsOf(batch, stops, runtime));

        // then
        assertThatThrownBy(() -> saving.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        changer.join();
        assertThat(saving.get(5, TimeUnit.SECONDS)).isZero();
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
    }

    @Test
    void 예보_계산_시작_후_품질_버전이_바뀌면_예보와_발행_기록과_정산_작업을_저장하지_않는다() {
        // given
        RuntimeSnapshot changing = runtime("quality-changed", input -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(routeVersionId);
                jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                    .param(routeId).update();
            });
            return RESULT;
        });

        // when
        int saved = writer.writeForecastsOf(batch, stops, changing);

        // then
        assertThat(saved).isZero();
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_evaluation_work WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isZero();
    }

    @Test
    void 품질_변경으로_예보를_저장하지_않은_배치는_다음_호출에서_다시_계산해_저장한다() {
        // given
        RuntimeSnapshot changing = runtime("retry-after-quality", input -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(routeVersionId);
                jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                    .param(routeId).update();
            });
            return RESULT;
        });
        assertThat(writer.writeForecastsOf(batch, stops, changing)).isZero();
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));

        // when
        int saved = writer.writeForecastsOf(batch, stops, runtime);

        // then
        assertThat(saved).isEqualTo(2);
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
    }

    @Test
    void 정산_작업_등록이_실패하면_저장한_예보와_발행_기록을_롤백하고_수집_입력을_확정하지_않는다() {
        // given
        jdbc.sql("""
            CREATE FUNCTION publication_work_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION '정산 작업 등록 실패';
            END;
            $$
            """).update();
        jdbc.sql("""
            CREATE TRIGGER publication_work_failure BEFORE INSERT ON forecast_evaluation_work
            FOR EACH ROW EXECUTE FUNCTION publication_work_failure()
            """).update();

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
            () -> writer.writeForecastsOf(batch, stops, runtime));

        // then
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_evaluation_work WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isZero();
    }

    @Test
    void 이미_열린_트랜잭션_안에서_예보_계산을_호출하면_실행하지_않는다() {
        // given
        var transaction = new TransactionTemplate(transactions);

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            transaction.executeWithoutResult(status -> writer.writeForecastsOf(batch, stops, runtime)));

        // then
        assertThat(failure).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
    }

    private RuntimeSnapshot runtime(String releaseId, SeatForecastModel model) {
        Instant dataUntil = OBSERVED_AT.minusSeconds(60);
        final long deploymentId = jdbc.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest, data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGED') RETURNING id
                """)
            .params(UUID.randomUUID(), releaseId, "seat-full-chance", "1.0.0",
                DIGEST, "SEAT_FULL_CHANCE_V1", CALCULATION_VERSION,
                DIGEST, offset(dataUntil))
            .query(Long.class).single();
        deploymentIds.add(deploymentId);
        return new RuntimeSnapshot(new ActiveModelDeployment(deploymentId, CALCULATION_VERSION, releaseId, DIGEST),
            new SupportedForecastScope(List.of("3330")), model, dataUntil);
    }

    private VehicleTrajectory trajectory(final long sourceObservationId) {
        return new VehicleTrajectory(sourceObservationId,
            new ObservedVehicle("vehicle-" + sourceObservationId, routeVersionId, 3, OBSERVED_AT, 12, null),
            new ObservedSeats.Known(12), new SeatSlope.Known(0),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new FullSeatStreak.SeenToEnd(0), 44);
    }

    private StoredPublication readPublication() {
        return jdbc.sql("""
                SELECT id, model_deployment_id, generated_at, published_at, prediction_count
                FROM forecast_publication WHERE source_batch_id = ?
                """)
            .param(batchId)
            .query((row, index) -> new StoredPublication(row.getLong("id"), row.getLong("model_deployment_id"), row.getObject("generated_at", OffsetDateTime.class).toInstant(),
                row.getObject("published_at", OffsetDateTime.class).toInstant(), row.getInt("prediction_count")))
            .single();
    }

    private List<StoredPrediction> readPredictions() {
        return jdbc.sql("""
                SELECT publication_id, vehicle_observation_id, target_stop_order, model_deployment_id,
                       seat_full_chance, expected_seats, generated_at
                FROM seat_forecast WHERE route_version_id = ? ORDER BY vehicle_observation_id, target_stop_order
                """)
            .param(routeVersionId)
            .query((row, index) -> new StoredPrediction(row.getLong("publication_id"),
                row.getLong("vehicle_observation_id"), row.getInt("target_stop_order"),
                row.getLong("model_deployment_id"), row.getDouble("seat_full_chance"),
                row.getDouble("expected_seats"), row.getObject("generated_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    private List<String> readEvaluationStates() {
        return jdbc.sql("""
                SELECT scoring_state FROM forecast_evaluation
                WHERE route_version_id = ? ORDER BY vehicle_observation_id, target_stop_order
                """)
            .param(routeVersionId).query(String.class).list();
    }

    private StoredCounts readCounts() {
        return jdbc.sql("""
                SELECT
                    (SELECT count(*) FROM forecast_publication WHERE source_batch_id = :batchId) AS publications,
                    (SELECT count(*) FROM seat_forecast WHERE route_version_id = :routeVersionId) AS predictions,
                    (SELECT count(*) FROM forecast_evaluation WHERE route_version_id = :routeVersionId) AS evaluations,
                    input_confirmed_at IS NOT NULL AS input_confirmed
                FROM observation_batch WHERE id = :batchId
                """)
            .param("batchId", batchId).param("routeVersionId", routeVersionId)
            .query((row, index) -> new StoredCounts(row.getLong("publications"), row.getLong("predictions"),
                row.getLong("evaluations"), row.getBoolean("input_confirmed")))
            .single();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record StoredPublication(long id, long modelDeploymentId,
                                     Instant generatedAt, Instant publishedAt, int predictionCount) { }

    private record StoredPrediction(long publicationId, long observationId, int targetStopOrder,
                                    long modelDeploymentId, double fullChance, double expectedSeats,
                                    Instant generatedAt) { }

    private record StoredCounts(long publications, long predictions, long evaluations, boolean inputConfirmed) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock publicationTransactionClock() {
            return CLOCK;
        }
    }
}
