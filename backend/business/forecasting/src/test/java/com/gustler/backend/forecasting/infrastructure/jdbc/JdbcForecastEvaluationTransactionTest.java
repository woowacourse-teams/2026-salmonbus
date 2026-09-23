package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.application.evaluation.ForecastEvaluationWriter;
import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesRepository;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
class JdbcForecastEvaluationTransactionTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-19T02:14:00Z");
    private static final Instant ARRIVED_AT = OBSERVED_AT.plusSeconds(360);
    private static final Instant SCORED_AT = ARRIVED_AT.plusSeconds(60);
    private static final int SOURCE_STOP_ORDER = 6;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int STOPS_TO_TARGET = TARGET_STOP_ORDER - SOURCE_STOP_ORDER;
    private static final double RAW_FULL_CHANCE = 0.41;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcForecastEvaluationRepository evaluations;

    @Autowired
    private ForecastEvaluationWriter evaluationWriter;

    @Autowired
    private CollectionInputs collectionInputs;

    @Autowired
    private JdbcSameDayFullOutcomesRepository outcomeRepository;

    @Autowired
    private SameDayFullOutcomesService outcomes;

    @Autowired
    private RouteDataQualityAccess qualityAccess;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long routeId;
    private long routeVersionId;
    private long modelId;
    private long sourceObservationId;
    private long arrivalObservationId;
    private long arrivalBatchId;

    @BeforeEach
    void 평가할_예측과_같은_차량의_도착_관측을_준비한다() {
        String routeKey = "evaluation-tx-" + UUID.randomUUID().toString().substring(0, 16);
        routeId = jdbc.sql("""
                INSERT INTO route(public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name)
                VALUES (?, 'GBIS', ?, '평가 검증', '출발', '도착') RETURNING id
                """).params(routeKey, routeKey).query(Long.class).single();
        routeVersionId = jdbc.sql("""
                INSERT INTO route_version(route_id, content_digest, valid_from)
                VALUES (?, ?, ?) RETURNING id
                """).params(routeId, "0".repeat(64), offset(OBSERVED_AT)).query(Long.class).single();
        for (int stopOrder : List.of(SOURCE_STOP_ORDER, TARGET_STOP_ORDER)) {
            jdbc.sql("""
                    INSERT INTO route_stop(route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                    VALUES (?, ?, ?, ?, 'UP', true)
                    """).params(routeVersionId, stopOrder, "stop-" + stopOrder, "정류장 " + stopOrder).update();
        }
        modelId = jdbc.sql("""
                INSERT INTO model_deployment(deployment_key, release_id, model_key, model_version,
                    bundle_digest, prediction_target_version, calculation_version,
                    supported_scope_digest, data_until, state)
                VALUES (?, ?, 'seat-full-chance', '1.0.0', ?, 'SEAT_FULL_CHANCE_V1',
                    'CALCULATION_V1', ?, ?, 'STAGED') RETURNING id
                """).params(UUID.randomUUID(), routeKey, "0".repeat(64), "0".repeat(64), offset(OBSERVED_AT))
            .query(Long.class).single();
        final long sourceBatchId = insertBatch(OBSERVED_AT);
        sourceObservationId = insertObservation(sourceBatchId, SOURCE_STOP_ORDER, 12);
        arrivalBatchId = insertBatch(ARRIVED_AT);
        arrivalObservationId = insertObservation(arrivalBatchId, TARGET_STOP_ORDER, 0);
        ForecastEvaluationTestData.saveForecasts(jdbc, List.of(new SeatForecast(
            sourceObservationId, routeVersionId, TARGET_STOP_ORDER, STOPS_TO_TARGET, modelId, 1,
            RAW_FULL_CHANCE, 0.38, 12.5, OBSERVED_AT.plusSeconds(1))));
    }

    @AfterEach
    void 이_테스트가_저장한_자료만_정리한다() {
        inTransaction(() -> {
            jdbc.sql("DELETE FROM forecast_evaluation WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM seat_forecast WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM forecast_publication WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM same_day_full_outcomes WHERE route_id = ?").param(routeId).update();
            jdbc.sql("""
                    DELETE FROM observation_trip_assignment WHERE observation_id IN (
                        SELECT id FROM vehicle_observation WHERE route_version_id = ?)
                    """).param(routeVersionId).update();
            jdbc.sql("DELETE FROM trip_quality_rebuild WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM vehicle_one_way_trip WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM observation_batch WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route_version_quality_policy WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route_data_quality WHERE route_id = ?").param(routeId).update();
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route_version WHERE id = ?").param(routeVersionId).update();
            jdbc.sql("DELETE FROM route WHERE id = ?").param(routeId).update();
            jdbc.sql("DELETE FROM model_deployment WHERE id = ?").param(modelId).update();
            return null;
        });
    }

    @Test
    void 보정_집계_저장에_실패하면_평가와_도착_입력_확정도_함께_취소한다() {
        // given 실제 집계 저장까지 수행한 뒤 실패시킨다.
        SameDayFullOutcomesService failingOutcomes =
            new SameDayFullOutcomesService(new FailingOutcomeRepository(outcomeRepository));
        ForecastEvaluationWriter failingWriter =
            new ForecastEvaluationWriter(evaluations, qualityAccess, collectionInputs, failingOutcomes);

        // when
        assertThatThrownBy(() -> inTransaction(() -> failingWriter.complete(List.of(completedEvaluation()))))
            .isInstanceOf(CalibrationWriteFailure.class);

        // then
        assertThat(evaluationState()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT input_confirmed_at IS NULL FROM observation_batch WHERE id = ?")
            .param(arrivalBatchId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM same_day_full_outcomes WHERE route_id = ?")
            .param(routeId).query(Long.class).single()).isZero();
    }

    @Test
    void 이미_완료한_평가를_다시_요청해도_다른_도착_배치의_입력을_확정하지_않는다() {
        // given 도착 관측 없이 완료된 평가는 다른 결과로 덮어쓸 수 없다.
        evaluationWriter.complete(List.of(ForecastEvaluation.completed(sourceObservationId, TARGET_STOP_ORDER,
            new ArrivalLabel.Skipped(), SCORED_AT)));

        // when
        List<SettledForecast> repeated = evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            sourceObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrivalObservationId, 0),
            SCORED_AT.plusSeconds(60))));

        // then
        assertThat(repeated).isEmpty();
        assertThat(evaluationState()).isEqualTo("SKIPPED");
        assertThat(jdbc.sql("SELECT input_confirmed_at IS NULL FROM observation_batch WHERE id = ?")
            .param(arrivalBatchId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM same_day_full_outcomes WHERE route_id = ?")
            .param(routeId).query(Long.class).single()).isZero();
    }

    @Test
    void 같은_평가를_동시에_확정해도_당일_집계에는_한_번만_반영한다() throws Exception {
        // given 빈 집계가 먼저 만들어진 경우에는 새 결과만 더한다.
        assertThat(outcomes.outcomesFor(routeId, SCORED_AT)).isEmpty();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> settleAfter(ready, start));
            var second = executor.submit(() -> settleAfter(ready, start));
            await(ready);

            // when
            start.countDown();
            final int firstChanged = first.get(10, TimeUnit.SECONDS);
            final int secondChanged = second.get(10, TimeUnit.SECONDS);

            // then
            assertThat(firstChanged + secondChanged).isEqualTo(1);
            assertCountedOnce();
        } finally {
            start.countDown();
            stop(executor);
        }
    }

    @Test
    void 당일_집계_초기화와_평가_확정이_겹쳐도_결과를_중복해서_세지_않는다() throws Exception {
        // given 초기화가 노선 잠금을 먼저 잡은 상태에서 평가를 시작한다.
        CountDownLatch initializationLocked = new CountDownLatch(1);
        CountDownLatch evaluationStarted = new CountDownLatch(1);
        CountDownLatch finishInitialization = new CountDownLatch(1);
        AtomicInteger initializationConnection = new AtomicInteger();
        AtomicInteger evaluationConnection = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var initialized = executor.submit(() -> inTransaction(() -> {
                initializationConnection.set(connectionId());
                qualityAccess.lockByRoute(routeId);
                initializationLocked.countDown();
                await(finishInitialization);
                return outcomes.outcomesFor(routeId, SCORED_AT);
            }));
            var settled = executor.submit(() -> {
                await(initializationLocked);
                return inTransaction(() -> {
                    evaluationConnection.set(connectionId());
                    evaluationStarted.countDown();
                    return settleAndRecord();
                });
            });

            // when
            await(evaluationStarted);
            awaitDatabaseLock(evaluationConnection.get(), initializationConnection.get());
            finishInitialization.countDown();
            Map<Integer, SameDayFullOutcomes> initialCounts = initialized.get(10, TimeUnit.SECONDS);
            final int changed = settled.get(10, TimeUnit.SECONDS);

            // then 초기화 이후의 평가가 한 번만 증분으로 반영된다.
            assertThat(initialCounts).isEmpty();
            assertThat(changed).isEqualTo(1);
            assertCountedOnce();
        } finally {
            initializationLocked.countDown();
            evaluationStarted.countDown();
            finishInitialization.countDown();
            stop(executor);
        }
    }

    private int settleAfter(CountDownLatch ready, CountDownLatch start) {
        return inTransaction(() -> {
            ready.countDown();
            await(start);
            return settleAndRecord();
        });
    }

    private int settleAndRecord() {
        return evaluationWriter.complete(List.of(completedEvaluation())).size();
    }

    private void assertCountedOnce() {
        assertThat(evaluationState()).isEqualTo("SETTLED");
        assertThat(outcomes.outcomesFor(routeId, SCORED_AT)).containsExactlyEntriesOf(
            Map.of(STOPS_TO_TARGET, new SameDayFullOutcomes(1, 1, RAW_FULL_CHANCE)));
        assertThat(jdbc.sql("SELECT sum(row_count) FROM same_day_full_outcomes WHERE route_id = ?")
            .param(routeId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT input_confirmed_at FROM observation_batch WHERE id = ?")
            .param(arrivalBatchId).query(OffsetDateTime.class).single().toInstant()).isEqualTo(SCORED_AT);
    }

    private String evaluationState() {
        return jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id = ?")
            .param(sourceObservationId).query(String.class).single();
    }

    private int connectionId() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private void awaitDatabaseLock(final int waitingConnection, final int holdingConnection)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT ? = ANY(pg_blocking_pids(?))")
                .params(holdingConnection, waitingConnection).query(Boolean.class).single()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("평가 트랜잭션이 초기화 트랜잭션의 잠금을 기다리지 않았다");
    }

    private ForecastEvaluation completedEvaluation() {
        return ForecastEvaluation.completed(sourceObservationId, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, 0), SCORED_AT);
    }

    private long insertBatch(Instant observedAt) {
        return jdbc.sql("""
                INSERT INTO observation_batch(route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, completed_at, outcome,
                    normalization_version, collection_strategy_version)
                VALUES (?, ?, 1, ?, ?, ?, ?, 'SUCCESS_ROWS', 'normalization-v1', 'collection-v1')
                RETURNING id
                """).params(routeVersionId, offset(observedAt), UUID.randomUUID().toString(),
                offset(observedAt), offset(observedAt), offset(observedAt))
            .query(Long.class).single();
    }

    private long insertObservation(final long batchId, final int stopOrder, final int seats) {
        final long observationId = jdbc.sql("""
                INSERT INTO vehicle_observation(observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order, running_state, remaining_seats)
                VALUES (?, ?, 0, 'evaluation-vehicle', ?, ?, ?, 2, ?) RETURNING id
                """).params(batchId, routeVersionId, stopOrder, "stop-" + stopOrder, stopOrder, seats)
            .query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, observationId);
    }

    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(10);
        return transaction.execute(status -> {
            jdbc.sql("SELECT set_config('lock_timeout', '5s', true)").query(String.class).single();
            jdbc.sql("SELECT set_config('statement_timeout', '5s', true)").query(String.class).single();
            return action.get();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행을 준비하지 못했다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 대기가 중단됐다", exception);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    /** DB에 실제 집계가 저장된 직후 발생하는 후속 처리 실패를 재현한다. */
    private static final class FailingOutcomeRepository implements SameDayFullOutcomesRepository {

        private final SameDayFullOutcomesRepository delegate;

        private FailingOutcomeRepository(SameDayFullOutcomesRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void lockRoute(final long routeId) {
            delegate.lockRoute(routeId);
        }

        @Override
        public List<SameDayFullOutcomeCount> findCounts(final long routeId, SeoulDay day) {
            return delegate.findCounts(routeId, day);
        }

        @Override
        public void upsertCounts(final long routeId, SeoulDay day, List<SameDayFullOutcomeCount> counts) {
            delegate.upsertCounts(routeId, day, counts);
            throw new CalibrationWriteFailure();
        }

        @Override
        public void add(SettledForecast settled) {
            delegate.add(settled);
            throw new CalibrationWriteFailure();
        }

        @Override
        public List<SameDayFullOutcomeCount> countFromSource(final long routeId, SeoulDay day, Instant until) {
            return delegate.countFromSource(routeId, day, until);
        }
    }

    private static final class CalibrationWriteFailure extends RuntimeException {
    }
}
