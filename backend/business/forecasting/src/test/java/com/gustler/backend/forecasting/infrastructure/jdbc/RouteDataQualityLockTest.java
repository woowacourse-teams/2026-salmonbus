package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
class RouteDataQualityLockTest {
    private final List<Long> routes = new ArrayList<>();
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RouteDataQualityAccess quality;
    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void 이번_테스트에서_만든_노선과_품질_행을_삭제한다() {
        for (final long route : routes) {
            jdbc.sql("DELETE FROM route_data_quality WHERE route_id=?").param(route).update();
            jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
        }
    }

    @Test
    void 품질_행을_잠근_트랜잭션이_끝나야_같은_노선의_품질_버전을_변경할_수_있다() throws Exception {
        // given
        final long route = route();
        inTransaction(() -> quality.lockByRoute(route));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger writerPid = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> holding = workers.submit(() -> inTransaction(() -> {
                holderPid.set(pid());
                quality.lockByRoute(route);
                acquired.countDown();
                await(release);
                return true;
            }));
            try {
                await(acquired);
                Future<Integer> changing = workers.submit(() -> inTransaction(() -> {
                    writerPid.set(pid());
                    attempted.countDown();
                    return jdbc.sql(
                    "UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                        .param(route)
                        .update();
                }));

                // when
                await(attempted);
                awaitBlocked(writerPid.get(), holderPid.get());

                // then
                assertThat(revision(route)).isEqualTo(1);
                release.countDown();
                assertThat(changing.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(holding.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(revision(route)).isEqualTo(2);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 한_노선의_품질_행을_잠근_상태에서도_다른_노선의_품질_행을_잠글_수_있다() throws Exception {
        // given
        final long first = route();
        final long second = route();

        // when
        whileLocked(first, () -> inTransaction(() -> {
            jdbc.sql("SET LOCAL lock_timeout='1s'").update();
            return quality.lockByRoute(second);
        }));

        // then
        assertThat(revision(second)).isEqualTo(1);
    }

    @Test
    void 이미_생성된_품질_행을_잠가도_같은_노선의_route_행은_잠글_수_있다() throws Exception {
        // given
        final long route = route();
        inTransaction(() -> quality.lockByRoute(route));

        // when
        whileLocked(route, () -> inTransaction(() ->
            jdbc.sql("SELECT id FROM route WHERE id=? FOR UPDATE NOWAIT")
                .param(route).query(Long.class).single()));

        // then
        assertThat(revision(route)).isEqualTo(1);
    }

    @Test
    void 같은_노선의_품질_행을_동시에_생성해도_행은_하나만_남고_두_요청_모두_처리를_완료한다() throws Exception {
        // given
        final long route = route();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Long> first = workers.submit(() -> inTransaction(() -> {
                holderPid.set(pid());
                final long version = quality.lockByRoute(route);
                inserted.countDown();
                await(release);
                return version;
            }));
            try {
                await(inserted);
                Future<Long> second = workers.submit(() -> inTransaction(() -> {
                    secondPid.set(pid());
                    attempted.countDown();
                    return quality.lockByRoute(route);
                }));

                // when
                await(attempted);
                awaitBlocked(secondPid.get(), holderPid.get());
                release.countDown();

                // then
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(jdbc.sql("SELECT count(*) FROM route_data_quality WHERE route_id=?")
                    .param(route).query(Long.class).single()).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 높은_ID의_노선을_잠가도_낮은_ID의_다른_노선의_품질_행은_잠글_수_있다() throws Exception {
        // given
        final long lower = route();
        final long higher = route();
        inTransaction(() -> quality.lockByRoute(lower));

        // when
        whileLocked(higher, () -> inTransaction(() ->
            jdbc.sql("SELECT route_id FROM route_data_quality WHERE route_id=? FOR UPDATE NOWAIT")
                .param(lower).query(Long.class).single()));

        // then
        assertThat(revision(lower)).isEqualTo(1);
    }

    private long route() {
        String source = "quality-lock-" + UUID.randomUUID().toString().substring(0, 16);
        final long id = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,'잠금 테스트','출발','도착') RETURNING id
            """).params(source, source).query(Long.class).single();
        routes.add(id);
        return id;
    }

    private long revision(final long route) {
        return jdbc.sql("SELECT quality_revision FROM route_data_quality WHERE route_id=?")
            .param(route).query(Long.class).single();
    }

    private int pid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private void whileLocked(final long route, Runnable action) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<Boolean> holding = worker.submit(() -> inTransaction(() -> {
                quality.lockByRoute(route);
                acquired.countDown();
                await(release);
                return true;
            }));
            try {
                await(acquired);
                action.run();
            } finally {
                release.countDown();
            }
            assertThat(holding.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("테스트 트랜잭션 진행 대기 초과");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private void awaitBlocked(final int waiting, final int holding) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            final boolean blocked = jdbc.sql("SELECT ? = ANY(pg_blocking_pids(?))")
                .params(holding, waiting).query(Boolean.class).single();
            if (blocked) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("품질 행 잠금 대기를 확인하지 못했다");
    }
}
