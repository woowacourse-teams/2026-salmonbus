package com.gustler.backend.observations.application;

import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.gbis.api.GbisLocationResult.NoVehicles;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.InputAlreadyConfirmedException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationRepository;
import com.gustler.backend.observations.domain.StaleCollectionAttemptException;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class CollectionInputConcurrencyTest {

    private static final int TIMEOUT_SECONDS = 5;
    private static final String ATTEMPT_KEY = "collection-input-concurrency";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime REQUESTED_AT = SCHEDULED_AT.plusSeconds(1);
    private static final OffsetDateTime RECEIVED_AT = SCHEDULED_AT.plusSeconds(2);
    private static final OffsetDateTime CONFIRMED_AT = SCHEDULED_AT.plusSeconds(3);

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private CollectionInputs inputs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    private long routeVersionId;

    @BeforeEach
    void 노선과_버전을_저장한다() {
        final long routeId = jdbc.sql("""
                INSERT INTO route (public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name)
                VALUES ('204000057', 'GBIS', '204000057', '3330', '범계역', '강남역')
                RETURNING id
                """).query(Long.class).single();
        routeVersionId = jdbc.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?) RETURNING id
                """).params(routeId, "0".repeat(64), SCHEDULED_AT).query(Long.class).single();
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        jdbc.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void 같은_계획을_동시에_예약하면_한_배치의_서로_다른_시도로_기록한다() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CollectionAttemptToken> first = workers.submit(() -> reserveAfterStart(ready, start));
            Future<CollectionAttemptToken> second = workers.submit(() -> reserveAfterStart(ready, start));
            await(ready);

            // when
            start.countDown();
            CollectionAttemptToken firstToken = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CollectionAttemptToken secondToken = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThat(firstToken.batchId()).isEqualTo(secondToken.batchId());
            assertThat(new int[] {firstToken.attemptNumber(), secondToken.attemptNumber()})
                .containsExactlyInAnyOrder(1, 2);
            assertThat(jdbc.sql("SELECT count(*) FROM observation_batch WHERE attempt_key = ?")
                .param(ATTEMPT_KEY).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT attempt_number FROM observation_batch WHERE id = ?")
                .param(firstToken.batchId()).query(Integer.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT sum(reserved_calls) FROM daily_call_quota")
                .query(Integer.class).single()).isEqualTo(2);
        } finally {
            start.countDown();
            stop(workers);
        }
    }

    @Test
    void 입력_확정이_먼저_잠그면_재수집은_확정_커밋을_기다린_뒤_거절된다() throws Exception {
        CollectionAttemptToken token = successfulAttempt();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch inputLocked = new CountDownLatch(1);
        CountDownLatch releaseInput = new CountDownLatch(1);
        CountDownLatch reopenStarted = new CountDownLatch(1);
        AtomicInteger confirmingPid = new AtomicInteger();
        AtomicInteger reopeningPid = new AtomicInteger();
        try {
            Future<?> confirmation = workers.submit(() -> transaction().executeWithoutResult(status -> {
                confirmingPid.set(backendPid());
                inputs.lockForForecast(token.batchId());
                inputs.confirmInput(token.batchId(), token.attemptNumber(), CONFIRMED_AT.toInstant());
                inputLocked.countDown();
                await(releaseInput);
            }));
            await(inputLocked);
            Future<CollectionAttemptToken> reopening = workers.submit(() -> transaction().execute(status -> {
                reopeningPid.set(backendPid());
                reopenStarted.countDown();
                return observations.openReserved(plan());
            }));
            await(reopenStarted);

            // when: PostgreSQL에서도 재수집 트랜잭션이 확정 트랜잭션을 기다리는지 확인한다.
            awaitBlockedBy(reopeningPid.get(), confirmingPid.get());
            releaseInput.countDown();
            confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThatThrownBy(() -> reopening.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InputAlreadyConfirmedException.class);
            assertThat(jdbc.sql("SELECT attempt_number FROM observation_batch WHERE id = ?")
                .param(token.batchId()).query(Integer.class).single()).isEqualTo(token.attemptNumber());
            assertThat(jdbc.sql("SELECT input_confirmed_at FROM observation_batch WHERE id = ?")
                .param(token.batchId()).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(CONFIRMED_AT.toInstant());
        } finally {
            releaseInput.countDown();
            stop(workers);
        }
    }

    @Test
    void 재수집이_먼저_잠그면_이전_입력의_확정은_재수집_커밋을_기다린_뒤_거절된다() throws Exception {
        CollectionAttemptToken previous = successfulAttempt();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch batchReopened = new CountDownLatch(1);
        CountDownLatch releaseReopen = new CountDownLatch(1);
        CountDownLatch confirmationStarted = new CountDownLatch(1);
        AtomicInteger reopeningPid = new AtomicInteger();
        AtomicInteger confirmingPid = new AtomicInteger();
        try {
            Future<CollectionAttemptToken> reopening = workers.submit(() -> transaction().execute(status -> {
                reopeningPid.set(backendPid());
                CollectionAttemptToken current = observations.openReserved(plan());
                batchReopened.countDown();
                await(releaseReopen);
                return current;
            }));
            await(batchReopened);
            Future<?> confirmation = workers.submit(() -> transaction().executeWithoutResult(status -> {
                confirmingPid.set(backendPid());
                confirmationStarted.countDown();
                inputs.lockForForecast(previous.batchId());
                inputs.confirmInput(previous.batchId(), previous.attemptNumber(), CONFIRMED_AT.toInstant());
            }));
            await(confirmationStarted);

            // when
            awaitBlockedBy(confirmingPid.get(), reopeningPid.get());
            releaseReopen.countDown();
            CollectionAttemptToken current = reopening.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // then
            assertThatThrownBy(() -> confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(StaleCollectionAttemptException.class);
            assertThat(current.batchId()).isEqualTo(previous.batchId());
            assertThat(current.attemptNumber()).isEqualTo(previous.attemptNumber() + 1);
            assertThat(jdbc.sql("SELECT outcome FROM observation_batch WHERE id = ?")
                .param(current.batchId()).query(String.class).single()).isEqualTo("RESERVED");
            assertThat(jdbc.sql("SELECT input_confirmed_at IS NULL FROM observation_batch WHERE id = ?")
                .param(current.batchId()).query(Boolean.class).single()).isTrue();
        } finally {
            releaseReopen.countDown();
            stop(workers);
        }
    }

    private CollectionAttemptToken reserveAfterStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return ledger.reserve(plan(), SCHEDULED_AT).token();
    }

    private CollectionAttemptToken successfulAttempt() {
        CollectionAttemptToken token = ledger.reserve(plan(), SCHEDULED_AT).token();
        ledger.markDispatching(token, SCHEDULED_AT, REQUESTED_AT);
        ledger.conclude(token, GbisObservationMapper.response(new NoVehicles("2026-08-19 11:14:02"), RECEIVED_AT));
        return token;
    }

    private CollectionPlan plan() {
        return new CollectionPlan(routeVersionId, SCHEDULED_AT, ATTEMPT_KEY);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(TIMEOUT_SECONDS);
        return transaction;
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private void awaitBlockedBy(final int waiterPid, final int blockerPid) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            final boolean blocked = jdbc.sql("SELECT ? = ANY(pg_blocking_pids(?))")
                .params(blockerPid, waiterPid).query(Boolean.class).single();
            if (blocked) {
                return;
            }
        }
        throw new AssertionError("트랜잭션이 수집 배치 잠금을 기다리지 않았다: " + waiterPid);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("트랜잭션 진행 신호를 기다리는 시간이 지났다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("트랜잭션 대기가 중단되었다", exception);
        }
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("동시성 검증 스레드 종료")
            .isTrue();
    }
}
