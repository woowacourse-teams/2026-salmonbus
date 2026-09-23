package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.gustler.backend.forecasting.application.model.TransactionalModelActivation;
import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ActiveModelSlot;
import com.gustler.backend.forecasting.domain.model.ModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelActivationConflictException;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 경쟁하는 호출마다 별도 트랜잭션을 사용하므로 테스트 전체를 트랜잭션으로 감싸지 않는다. */
@IntegrationTest
class ModelActivationConcurrencyTest {

    @Autowired
    private TransactionalModelActivation activation;

    @Autowired
    private JdbcModelDeploymentRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String releasePrefix;
    private ActiveModelSlot originalSlot;
    private Optional<ActiveModelDeployment> originalActive;

    @BeforeEach
    void 테스트용_활성_슬롯을_준비한다() {
        releasePrefix = "activation-test-" + UUID.randomUUID() + "-";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            originalSlot = repository.lockActiveSlot();
            originalActive = repository.findActive();
            jdbcClient.sql("UPDATE model_deployment SET state = 'RETIRED' WHERE state = 'ACTIVE'").update();
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = NULL, version = 0 WHERE id = 1")
                .update();
        });
    }

    @AfterEach
    void 테스트에서_생성한_배포만_정리하고_원래_슬롯을_복원한다() {
        if (originalSlot == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.lockActiveSlot();
            jdbcClient.sql("""
                    DELETE FROM model_activation_request
                    WHERE target_deployment_id IN (
                        SELECT id FROM model_deployment WHERE release_id LIKE :prefix)
                    """).param("prefix", releasePrefix + "%").update();
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = NULL WHERE id = 1").update();
            jdbcClient.sql("DELETE FROM model_deployment WHERE release_id LIKE :prefix")
                .param("prefix", releasePrefix + "%").update();
            originalActive.ifPresent(active -> jdbcClient.sql("UPDATE model_deployment SET state = 'ACTIVE' WHERE id = ?")
                .param(active.id()).update());
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = :id, version = :version WHERE id = 1")
                .param("id", originalSlot.deployment().map(ActiveModelDeployment::id).orElse(null), java.sql.Types.BIGINT)
                .param("version", originalSlot.version()).update();
        });
    }

    @Test
    void 첫_모델을_동시에_활성화해도_한_요청만_성공한다() throws Exception {
        // when
        List<ActivationAttempt> attempts = compete(0);

        // then
        assertSingleWinner(attempts, 1);
        assertThat(deploymentCount()).isOne();
        assertThat(requestCount()).isOne();
    }

    @Test
    void 같은_활성_버전을_전제로_한_두_교체_중_하나만_적용한다() throws Exception {
        // given
        ModelActivation previous = activation.activate(UUID.randomUUID(), 0, identity("previous"));

        // when
        List<ActivationAttempt> attempts = compete(previous.resultingActiveVersion());

        // then
        assertSingleWinner(attempts, 2);
        assertThat(stateOf(previous.target().id())).isEqualTo("RETIRED");
        assertThat(deploymentCount()).isEqualTo(2);
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void 슬롯_잠금을_기다린_후속_요청은_커밋된_모델을_읽고_다음_버전으로_교체한다() throws Exception {
        // given
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        CompletableFuture<Integer> waitingConnection = new CompletableFuture<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ModelActivation> first = executor.submit(() -> transaction.execute(status -> {
                ModelActivation result = activation.activate(UUID.randomUUID(), 0, identity("first"));
                firstWritten.countDown();
                await(commitFirst);
                return result;
            }));
            try {
                assertThat(firstWritten.await(5, TimeUnit.SECONDS)).isTrue();
                Future<ModelActivation> next = executor.submit(() -> transaction.execute(status -> {
                    waitingConnection.complete(jdbcClient.sql("SELECT pg_backend_pid()")
                        .query(Integer.class).single());
                    return activation.activate(UUID.randomUUID(), 1, identity("next"));
                }));
                final int waitingPid = waitingConnection.get(5, TimeUnit.SECONDS);

                // when: 후속 요청이 실제로 DB 잠금을 기다리는 것을 확인한 뒤 첫 요청을 커밋한다.
                awaitSlotLock(waitingPid);
                commitFirst.countDown();
                ModelActivation firstResult = first.get(10, TimeUnit.SECONDS);
                ModelActivation nextResult = next.get(10, TimeUnit.SECONDS);

                // then
                assertThat(nextResult.resultingActiveVersion()).isEqualTo(2);
                assertThat(stateOf(firstResult.target().id())).isEqualTo("RETIRED");
                assertThat(stateOf(nextResult.target().id())).isEqualTo("ACTIVE");
                assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(2,
                    Optional.of(nextResult.target())));
                assertThat(jdbcClient.sql("SELECT predecessor_deployment_id FROM model_deployment WHERE id = ?")
                    .param(nextResult.target().id()).query(Long.class).single()).isEqualTo(firstResult.target().id());
                assertThat(deploymentCount()).isEqualTo(2);
                assertThat(requestCount()).isEqualTo(2);
            } finally {
                commitFirst.countDown();
            }
        }
    }

    @Test
    void 같은_요청이_동시에_들어오면_한_번_변경하고_같은_결과를_돌려준다() throws Exception {
        // given
        UUID requestId = UUID.randomUUID();
        ModelIdentity identity = identity("same-request");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ActivationAttempt> first = executor.submit(() -> activateAfter(ready, start, requestId, 0, identity));
            Future<ActivationAttempt> second = executor.submit(() -> activateAfter(ready, start, requestId, 0, identity));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ActivationAttempt firstResult = first.get(10, TimeUnit.SECONDS);
            ActivationAttempt secondResult = second.get(10, TimeUnit.SECONDS);

            // then
            assertThat(firstResult.conflict()).isNull();
            assertThat(secondResult.conflict()).isNull();
            assertThat(firstResult.result()).isEqualTo(secondResult.result());
        }
        assertThat(repository.findActiveSlot().version()).isOne();
        assertThat(deploymentCount()).isOne();
        assertThat(requestCount()).isOne();
    }

    @Test
    void 다른_모델로_교체한_뒤_이전_요청을_재전송해도_원래_결과를_반환한다() {
        // given
        UUID firstRequest = UUID.randomUUID();
        ModelIdentity firstIdentity = identity("first");
        ModelActivation first = activation.activate(firstRequest, 0, firstIdentity);
        ModelActivation second = activation.activate(UUID.randomUUID(), 1, identity("second"));

        // when
        ModelActivation replay = activation.activate(firstRequest, 0, firstIdentity);

        // then
        assertThat(replay).isEqualTo(first);
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(2, Optional.of(second.target())));
        assertThat(deploymentCount()).isEqualTo(2);
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void 같은_요청_ID로_다른_모델을_요청하면_거절한다() {
        // given
        UUID requestId = UUID.randomUUID();
        ModelActivation first = activation.activate(requestId, 0, identity("first"));

        // when, then
        assertThatThrownBy(() -> activation.activate(requestId, 0, identity("different")))
            .isInstanceOf(ModelActivationConflictException.class);
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(1, Optional.of(first.target())));
        assertThat(deploymentCount()).isOne();
        assertThat(requestCount()).isOne();
    }

    @Test
    void 같은_요청_ID의_기대_버전이_달라지면_거절한다() {
        // given
        UUID requestId = UUID.randomUUID();
        ModelIdentity identity = identity("first");
        ModelActivation first = activation.activate(requestId, 0, identity);

        // when, then
        assertThatThrownBy(() -> activation.activate(requestId, 1, identity))
            .isInstanceOf(ModelActivationConflictException.class);
        assertThat(repository.findActivation(requestId)).contains(first);
        assertThat(repository.findActiveSlot().version()).isOne();
    }

    @Test
    void 현재_모델을_새_요청으로_선택하면_버전과_배포는_유지하고_요청만_기록한다() {
        // given
        ModelIdentity identity = identity("unchanged");
        ModelActivation first = activation.activate(UUID.randomUUID(), 0, identity);
        UUID requestId = UUID.randomUUID();

        // when
        ModelActivation unchanged = activation.activate(requestId, 1, identity);

        // then
        assertThat(unchanged.target()).isEqualTo(first.target());
        assertThat(unchanged.expectedActiveVersion()).isOne();
        assertThat(unchanged.resultingActiveVersion()).isOne();
        assertThat(repository.findActivation(requestId)).contains(unchanged);
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(1, Optional.of(first.target())));
        assertThat(deploymentCount()).isOne();
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void 같은_모델이어도_기대_버전이_다르면_새_요청을_거절한다() {
        // given
        ModelIdentity identity = identity("unchanged");
        activation.activate(UUID.randomUUID(), 0, identity);
        UUID staleRequest = UUID.randomUUID();

        // when, then
        assertThatThrownBy(() -> activation.activate(staleRequest, 0, identity))
            .isInstanceOf(ModelActivationConflictException.class);
        assertThat(repository.findActivation(staleRequest)).isEmpty();
        assertThat(repository.findActiveSlot().version()).isOne();
    }

    @Test
    void 요청_기록_후_실패하면_배포와_슬롯과_요청이_함께_롤백된다() {
        // given
        ModelActivation first = activation.activate(UUID.randomUUID(), 0, identity("first"));
        JdbcModelDeploymentRepository target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        JdbcModelDeploymentRepository failingRepository = spy(target);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("활성화 저장 실패 테스트");
        }).when(failingRepository).recordActivation(any(ModelActivation.class));
        TransactionalModelActivation failingActivation = new TransactionalModelActivation(failingRepository,
            Clock.fixed(Instant.parse("2026-09-23T01:02:03Z"), ZoneOffset.UTC));
        UUID failedRequest = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // when
        assertThatThrownBy(() -> transaction.execute(status ->
            failingActivation.activate(failedRequest, 1, identity("failed"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("활성화 저장 실패 테스트");

        // then
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(1, Optional.of(first.target())));
        assertThat(stateOf(first.target().id())).isEqualTo("ACTIVE");
        assertThat(repository.findActivation(failedRequest)).isEmpty();
        assertThat(deploymentCount()).isOne();
        assertThat(requestCount()).isOne();
    }

    private void awaitSlotLock(int backendPid) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final boolean waiting = jdbcClient.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid = :pid AND wait_event_type = 'Lock'
                          AND query LIKE '%model_active_slot%')
                    """).param("pid", backendPid).query(Boolean.class).single();
            if (waiting) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("후속 모델 요청이 활성 슬롯 잠금을 기다리지 않았습니다");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 모델 활성화의 커밋 대기 시간이 초과됐습니다");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("모델 활성화 경쟁 테스트가 중단됐습니다", error);
        }
    }

    private List<ActivationAttempt> compete(long expectedVersion) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ActivationAttempt> first = executor.submit(() ->
                activateAfter(ready, start, UUID.randomUUID(), expectedVersion, identity("candidate-a")));
            Future<ActivationAttempt> second = executor.submit(() ->
                activateAfter(ready, start, UUID.randomUUID(), expectedVersion, identity("candidate-b")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private ActivationAttempt activateAfter(CountDownLatch ready, CountDownLatch start, UUID requestId,
                                            long expectedVersion, ModelIdentity identity) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("모델 활성화 경쟁 테스트 시작을 기다리다 시간이 초과됐습니다");
        }
        try {
            return new ActivationAttempt(activation.activate(requestId, expectedVersion, identity), null);
        } catch (ModelActivationConflictException conflict) {
            return new ActivationAttempt(null, conflict);
        }
    }

    private void assertSingleWinner(List<ActivationAttempt> attempts, long expectedVersion) {
        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> attempt.conflict() != null).hasSize(1);
        ModelActivation winner = attempts.stream().map(ActivationAttempt::result)
            .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(expectedVersion,
            Optional.of(winner.target())));
        assertThat(jdbcClient.sql("SELECT count(*) FROM model_deployment WHERE state = 'ACTIVE'")
            .query(Integer.class).single()).isOne();
    }

    private ModelIdentity identity(String label) {
        return new ModelIdentity(releasePrefix + label, "seat-distribution-a18", "model-v1.0.0", "0".repeat(64),
            "seat-distribution-0-70", "seat-feature-contract-v1", "1".repeat(64),
            Instant.parse("2026-08-30T14:59:56Z"));
    }

    private int deploymentCount() {
        return jdbcClient.sql("SELECT count(*) FROM model_deployment WHERE release_id LIKE :prefix")
            .param("prefix", releasePrefix + "%").query(Integer.class).single();
    }

    private int requestCount() {
        return jdbcClient.sql("""
                SELECT count(*) FROM model_activation_request a
                JOIN model_deployment d ON d.id = a.target_deployment_id
                WHERE d.release_id LIKE :prefix
                """).param("prefix", releasePrefix + "%").query(Integer.class).single();
    }

    private String stateOf(long id) {
        return jdbcClient.sql("SELECT state FROM model_deployment WHERE id = ?")
            .param(id).query(String.class).single();
    }

    private record ActivationAttempt(ModelActivation result, ModelActivationConflictException conflict) { }
}
