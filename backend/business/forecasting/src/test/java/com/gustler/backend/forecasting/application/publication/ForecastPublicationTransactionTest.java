package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore;
import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.publication.ForecastPublicationTransactionTest.FixedClock;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPoint;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak.SeenToEnd;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Known;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle.Unknown;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.observations.api.CollectionInputs;
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
import org.assertj.core.api.Assertions;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 외부 테스트 트랜잭션 없이 실제 발행의 커밋·롤백과 수집 배치 잠금을 검증한다. */
@ForecastingIntegrationTest
@Import(FixedClock.class)
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

    @Autowired
    private ForecastPublicationRepository publications;

    @Autowired
    private EvaluationWorkStore work;

    @Autowired
    private CollectionInputs inputs;

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
        String routeKey = "publication-" + UUID.randomUUID().toString().substring(0, 12);
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
            jdbc.sql("DELETE FROM forecast_evaluation_result WHERE route_version_id = ?")
                .param(routeVersionId)
                .update();
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
        for (final long deploymentId : deploymentIds) {
            jdbc.sql("DELETE FROM model_deployment WHERE id = ?").param(deploymentId).update();
        }
    }

    @Test
    void 상세_저장을_줄여도_모든_예보를_정산_대상으로_남긴다() {
        // given
        EvaluationSamplingSelector sampling = new EvaluationSamplingSelector(true, "", Duration.ofSeconds(60));
        List<EvaluationSamplingSource> source = samplingSources(OBSERVED_AT.minusSeconds(15));
        sampling.committed(sampling.select(samplingPublication(), source));
        ForecastBatchCommitter committer = new ForecastBatchCommitter(publications, work, quality, inputs, sampling);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            committer.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT)));

        // then
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
        assertThat(
            jdbc.sql("SELECT target_stop_order FROM forecast_evaluation_pending WHERE vehicle_observation_id = ?")
                .param(observationId)
                .query(Integer.class)
                .list()).containsExactlyInAnyOrder(4, 5);
        assertThat(
            jdbc.sql(
            "SELECT evaluation_detail_required FROM seat_forecast WHERE vehicle_observation_id = ? ORDER BY target_stop_order")
                .param(observationId)
                .query(Boolean.class)
                .list()).containsExactly(true, false);
    }

    @Test
    void 트랜잭션이_롤백되면_평가_선택_기준도_갱신하지_않는다() {
        // given
        EvaluationSamplingSelector sampling = new EvaluationSamplingSelector(true, "", Duration.ofSeconds(60));
        ForecastBatchCommitter committer = new ForecastBatchCommitter(publications, work, quality, inputs, sampling);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            committer.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT));
            status.setRollbackOnly();
        });

        // then
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
        assertThat(sampling.select(samplingPublication(), samplingSources(OBSERVED_AT.plusSeconds(15)))
                .publication().predictions()).allMatch(
                    SeatForecast::evaluationDetailRequired);
    }

    @Test
    void 기능을_꺼도_재발행한_기존_예보의_상세_저장_선택은_유지한다() {
        // given
        EvaluationSamplingSelector sampling = new EvaluationSamplingSelector(true, "", Duration.ofSeconds(60));
        sampling.committed(sampling.select(samplingPublication(), samplingSources(OBSERVED_AT.minusSeconds(15))));
        ForecastBatchCommitter enabled = new ForecastBatchCommitter(publications, work, quality, inputs, sampling);
        ForecastBatchCommitter disabled = new ForecastBatchCommitter(publications, work, quality, inputs,
            new EvaluationSamplingSelector(false, "", Duration.ofSeconds(60)));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            enabled.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT)));

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            disabled.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT)));

        // then
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
        assertThat(
            jdbc.sql(
            "SELECT evaluation_detail_required FROM seat_forecast WHERE vehicle_observation_id = ? ORDER BY target_stop_order")
                .param(observationId)
                .query(Boolean.class)
                .list()).containsExactly(true, false);
    }

    @Test
    void 재시작_후_재발행은_새_전체_평가_표본으로_기억하지_않는다() {
        // given
        EvaluationSamplingSelector sampling = new EvaluationSamplingSelector(true, "", Duration.ofSeconds(60));
        sampling.committed(sampling.select(samplingPublication(), samplingSources(OBSERVED_AT.minusSeconds(15))));
        ForecastBatchCommitter original = new ForecastBatchCommitter(publications, work, quality, inputs, sampling);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            original.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT)));
        EvaluationSamplingSelector restarted = new EvaluationSamplingSelector(true, "", Duration.ofSeconds(60));
        ForecastBatchCommitter committer = new ForecastBatchCommitter(publications, work, quality, inputs, restarted);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            committer.commit(batch, samplingPublication(), samplingSources(OBSERVED_AT)));

        // then
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
        assertThat(
            jdbc.sql(
            "SELECT evaluation_detail_required FROM seat_forecast WHERE vehicle_observation_id = ? ORDER BY target_stop_order")
                .param(observationId)
                .query(Boolean.class)
                .list()).containsExactly(true, false);
        assertThat(restarted.select(samplingPublication(), samplingSources(OBSERVED_AT.plusSeconds(15)))
                .publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
    }

    private ForecastPublication samplingPublication() {
        return new ForecastPublication(
            batchId, routeVersionId, runtime.deploymentId(), 0, 1, OBSERVED_AT, GENERATED_AT, GENERATED_AT,
            List.of(
                new SeatForecast(
                    observationId, routeVersionId, 4, 1, runtime.deploymentId(), 0, .4, .4, 12.0, GENERATED_AT),
                new SeatForecast(
                    observationId, routeVersionId, 5, 2, runtime.deploymentId(), 0, .4, .4, 12.0, GENERATED_AT)));
    }

    private List<EvaluationSamplingSource> samplingSources(Instant observedAt) {
        return List.of(new EvaluationSamplingSource(observationId, "3330",
            new EvaluationSamplingPoint(
                routeVersionId, runtime.deploymentId(), "vehicle-1", 3, "UP", observedAt)));
    }

    @Test
    void 발행과_예측과_평가를_함께_저장하고_같은_배치를_다시_발행하면_새_계산으로_덮어쓴다() {
        // when
        writer.writeForecastsOf(batch, stops, runtime);

        // then
        StoredPublication publication = readPublication();
        List<StoredPrediction> predictions = readPredictions();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(publication.modelDeploymentId()).isEqualTo(runtime.deploymentId());
            softly.assertThat(publication.publishedAt()).isEqualTo(GENERATED_AT);
            softly.assertThat(publication.predictionCount()).isEqualTo(2);
        }
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
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(republished.id()).isEqualTo(publication.id());
            softly.assertThat(republished.modelDeploymentId()).isEqualTo(replacement.deploymentId());
            softly.assertThat(republished.predictionCount()).isEqualTo(2);
        }
        assertThat(readPredictions()).containsExactly(
            new StoredPrediction(publication.id(), observationId, 4, replacement.deploymentId(), 0.7, 0.3,
                GENERATED_AT),
            new StoredPrediction(publication.id(), observationId, 5, replacement.deploymentId(), 0.7, 0.3,
                GENERATED_AT));
        assertThat(readEvaluationStates()).containsExactly("PENDING", "PENDING");
        assertThat(readCounts()).isEqualTo(new StoredCounts(1, 2, 2, true));
    }

    @Test
    void 일부_예측을_저장한_뒤_실패하면_발행과_평가와_입력_확정도_롤백한다() {
        // given
        when(trajectories.readTrajectories(batchId))
            .thenReturn(List.of(trajectory(observationId), trajectory(MISSING_OBSERVATION_ID)));

        // when
        Throwable caughtFailure = catchThrowable(() -> writer.writeForecastsOf(batch, stops, runtime));

        // then
        assertThat(caughtFailure)
            .isNotNull()
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
        final int saved = writer.writeForecastsOf(batch, stops, checking);

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
        Thread holder = Thread.ofPlatform()
            .start(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(routeVersionId);
                locked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
        try {

            // when
            final int saved =
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> writer.writeForecastsOf(batch, stops, runtime));

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
        Thread changer = Thread.ofPlatform()
            .start(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(routeVersionId);
                jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                    .param(routeId).update();
                changed.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        assertThat(changed.await(2, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableFuture<Integer> saving =
            CompletableFuture.supplyAsync(() -> writer.writeForecastsOf(batch, stops, runtime));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> saving.get(300, TimeUnit.MILLISECONDS));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(TimeoutException.class);
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
        final int saved = writer.writeForecastsOf(batch, stops, changing);

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
        final int saved = writer.writeForecastsOf(batch, stops, runtime);

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
        Throwable failure = Assertions.catchThrowable(
            () -> writer.writeForecastsOf(batch, stops, runtime));

        // then
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(readCounts()).isEqualTo(new StoredCounts(0, 0, 0, false));
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_evaluation_work WHERE route_version_id=?")
                .param(routeVersionId).query(Long.class).single()).isZero();
    }

    @Test
    void 이미_열린_트랜잭션_안에서_예보_계산을_호출하면_실행하지_않는다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        // when
        Throwable failure = Assertions.catchThrowable(() ->
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
            new Known(12), new SeatSlope.Known(0),
            new Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new SeenToEnd(0), 44);
    }

    private StoredPublication readPublication() {
        return jdbc.sql("""
                SELECT id, model_deployment_id, generated_at, published_at, prediction_count
                FROM forecast_publication WHERE source_batch_id = ?
                """)
            .param(batchId)
            .query((row, index) ->
                new StoredPublication(row.getLong("id"), row.getLong("model_deployment_id"),
                    row.getObject("generated_at", OffsetDateTime.class)
                        .toInstant(),
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
        Instant generatedAt, Instant publishedAt, int predictionCount) {
    }

    private record StoredPrediction(long publicationId, long observationId, int targetStopOrder,
        long modelDeploymentId, double fullChance, double expectedSeats,
        Instant generatedAt) {
    }

    private record StoredCounts(long publications, long predictions, long evaluations, boolean inputConfirmed) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock publicationTransactionClock() {
            return CLOCK;
        }
    }
}
