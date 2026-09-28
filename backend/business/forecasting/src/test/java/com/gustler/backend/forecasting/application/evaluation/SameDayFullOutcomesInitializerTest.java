package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 공통 실제 DB/작업 fixture를 사용하며 기존 원자성 명세도 함께 실행한다. */
class SameDayFullOutcomesInitializerTest extends SameDayTransactionBoundaryTest {
    private SameDayFullOutcomesInitializer initializer;
    private final SeoulDay day = SeoulDay.containing(NOW);

    @BeforeEach
    void 실제_초기화_빈의_트랜잭션_프록시를_준비한다() {
        initializer = context.getBean(SameDayFullOutcomesInitializer.class);
    }

    @Test
    void 초기집계와_정산이_겹쳐도_각_정산은_한번만_합계에_반영된다() throws Exception {
        committedSettlementWithoutCounts();
        long secondBatch = insertBatch(OBSERVED_AT.plusSeconds(2));
        long secondSource = insertObservation(secondBatch, 1, 12);
        publish(secondBatch, OBSERVED_AT.plusSeconds(2), new SeatForecast(secondSource, versionId, 2, 1, modelId, 1,
            .25, .25, 12.5, OBSERVED_AT.plusSeconds(3)));
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseSource(read, release);
        Future<?> seed = workers.submit(() -> initializer.initialize(routeId, day));
        Future<?> settling;
        try {
            await(read);
            settling = workers.submit(settlement::settleArrivalLabels);
            awaitLockWait();
            assertThat(count("same_day_full_outcomes")).isZero();
        } finally {
            release.countDown();
        }
        seed.get(5, TimeUnit.SECONDS);
        settling.get(5, TimeUnit.SECONDS);
        assertThat(total()).isEqualTo(2);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(2);
    }

    @Test
    void 동시에_초기화를_요청해도_잠금_획득_후_다시_확인해_원본은_한번만_센다() throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseSource(read, release);
        Future<?> first = workers.submit(() -> initializer.initialize(routeId, day));
        Future<Boolean> second;
        try {
            await(read);
            second = workers.submit(() -> initializer.initialize(routeId, day));
            awaitLockWait();
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        verify(countsSpy, times(1)).countFromSource(routeId, day, day.end());
    }

    @Test
    void 초기집계_SQL이_실패하면_집계를_남기지_않고_잠금도_해제한다() {
        doAnswer(call -> {
            jdbc.sql("SELECT pg_sleep(1)").query().singleRow();
            return call.callRealMethod();
        }).when(countsSpy).countFromSource(anyLong(), any(), any());
        assertThatThrownBy(() -> initializer.initialize(routeId, day)).isInstanceOf(QueryTimeoutException.class);
        assertThat(count("same_day_full_outcomes")).isZero();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
            jdbc.sql("SELECT quality_revision FROM route_data_quality WHERE route_id = ? FOR UPDATE NOWAIT")
                .param(routeId).query(Long.class).single());
    }

    @Test
    void 초기집계_재시도는_이미_커밋한_정산을_누락하거나_중복하지_않는다() {
        committedSettlementWithoutCounts();
        doAnswer(call -> { timeout(); return call.callRealMethod(); })
            .when(countsSpy).countFromSource(anyLong(), any(), any());
        assertThatThrownBy(() -> initializer.initialize(routeId, day)).isInstanceOf(QueryTimeoutException.class);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        doAnswer(call -> call.callRealMethod()).when(countsSpy).countFromSource(anyLong(), any(), any());
        assertThat(initializer.initialize(routeId, day)).isTrue();
        assertThat(initializer.initialize(routeId, day)).isFalse();
        assertThat(total()).isEqualTo(1);
        assertThat(countsSpy.countFromSource(routeId, day, day.end()).getFirst().rowCount()).isEqualTo(1);
    }

    @Test
    void 원본이_0건이어도_초기화를_완료하고_재시도에서_다시_세지_않는다() {
        assertThat(initializer.activeRouteIds()).containsExactly(routeId);
        assertThat(initializer.initialize(routeId, day)).isTrue();
        assertThat(initializer.initialize(routeId, day)).isFalse();
        assertThat(countsSpy.findCounts(routeId, day)).containsExactly(new SameDayFullOutcomeCount(0, 0, 0, 0, day.start()));
        verify(countsSpy, times(1)).countFromSource(routeId, day, day.end());
    }

    @Test
    void 초기화가_완료되기_전에는_일부_거리의_집계만_다른_트랜잭션에_보이지_않는다() throws Exception {
        committedSettlementWithoutCounts();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            call.callRealMethod();
            written.countDown();
            await(release);
            return null;
        }).when(countsSpy).upsertCounts(anyLong(), any(), any());
        Future<?> seed = workers.submit(() -> initializer.initialize(routeId, day));
        try {
            await(written);
            assertThat(count("same_day_full_outcomes")).isZero();
        } finally {
            release.countDown();
        }
        seed.get(5, TimeUnit.SECONDS);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void 초기화_중_품질_변경은_같은_노선_잠금으로_직렬화된다() throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseSource(read, release);
        Future<?> seed = workers.submit(() -> initializer.initialize(routeId, day));
        Future<?> quality;
        try {
            await(read);
            quality = workers.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> {
                    context.getBean(RouteDataQualityAccess.class).lock(versionId);
                    jdbc.sql("UPDATE route_data_quality SET quality_revision = quality_revision + 1 WHERE route_id = ?")
                        .param(routeId).update();
                }));
            awaitLockWait();
        } finally {
            release.countDown();
        }
        seed.get(5, TimeUnit.SECONDS);
        quality.get(5, TimeUnit.SECONDS);
        assertThat(countsSpy.findCounts(routeId, day)).isEmpty();
        assertThat(initializer.initialize(routeId, day)).isTrue();
        assertThat(countsSpy.findCounts(routeId, day)).hasSize(1);
    }

    private void committedSettlementWithoutCounts() {
        savePending();
        initializeEmptyDay();
        settlement.settleArrivalLabels();
        jdbc.sql("TRUNCATE same_day_full_outcomes").update();
    }

    private void pauseSource(CountDownLatch read, CountDownLatch release) {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                await(release);
            }
            return result;
        }).when(countsSpy).countFromSource(anyLong(), any(), any());
    }

    private void awaitLockWait() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0)")
                .query(Boolean.class).single()) { return; }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("실제 PostgreSQL 잠금 대기에 도달하지 않았다");
    }

    private long total() {
        return jdbc.sql("SELECT coalesce(sum(row_count),0) FROM same_day_full_outcomes").query(Long.class).single();
    }
}
