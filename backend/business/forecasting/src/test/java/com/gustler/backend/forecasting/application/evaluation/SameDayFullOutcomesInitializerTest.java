package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.util.List;
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
    private final SeoulDay day = SeoulDay.containing(NOW);
    private SameDayFullOutcomesInitializer initializer;

    @BeforeEach
    void 실제_초기화_빈의_트랜잭션_프록시를_준비한다() {
        initializer = context.getBean(SameDayFullOutcomesInitializer.class);
    }

    @Test
    void 초기집계와_정산이_겹쳐도_각_정산은_한번만_합계에_반영된다() throws Exception {
        // when
        committedSettlementWithoutCounts();
        final long secondBatch = insertBatch(OBSERVED_AT.plusSeconds(2));
        final long secondSource = insertObservation(secondBatch, 1, 12);
        publish(secondBatch, OBSERVED_AT.plusSeconds(2), new SeatForecast(secondSource, versionId, 2, 1, modelId, 1,
            .25, .25, 12.5, OBSERVED_AT.plusSeconds(3)));
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseSource(read, release);
        Future<?> seed = workers.submit(() -> initializer.initialize(routeId, day));
        Future<?> settling;
        try {
            await(read);
            settling = workers.submit(settlement::settleArrivalLabels);
            awaitLockWait();

            // then
            assertThat(count("same_day_model_full_outcomes")).isZero();
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
        // when
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
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

        // then
        assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        verify(countsSpy, times(1)).countFromSource(routeId, modelId, day, day.end());
    }

    @Test
    void 초기집계_SQL이_실패하면_집계를_남기지_않고_잠금도_해제한다() {
        // when
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?) ON CONFLICT DO NOTHING")
            .param(routeId).update();
        doAnswer(call -> {
            jdbc.sql("SELECT pg_sleep(1)").query().singleRow();
            return call.callRealMethod();
        }).when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> initializer.initialize(routeId, day));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(QueryTimeoutException.class);
        assertThat(count("same_day_model_full_outcomes")).isZero();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
            jdbc.sql("SELECT route_id FROM route_data_quality WHERE route_id = ? FOR UPDATE NOWAIT")
                .param(routeId)
                .query(Long.class)
                .single());
    }

    @Test
    void 초기집계_재시도는_이미_커밋한_정산을_누락하거나_중복하지_않는다() {
        // when
        committedSettlementWithoutCounts();
        doAnswer(call -> {
            timeout();
            return call.callRealMethod();
        })
            .when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> initializer.initialize(routeId, day));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(QueryTimeoutException.class);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        doAnswer(call -> call.callRealMethod()).when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());
        assertThat(initializer.initialize(routeId, day)).isTrue();
        assertThat(initializer.initialize(routeId, day)).isFalse();
        assertThat(total()).isEqualTo(1);
        assertThat(countsSpy.countFromSource(routeId, modelId, day, day.end()).getFirst().rowCount()).isEqualTo(1);
    }

    @Test
    void 원본이_0건이어도_초기화를_완료하고_재시도에서_다시_세지_않는다() {
        // when
        List<Long> activeRouteIds = initializer.activeRouteIds();

        // then
        assertThat(activeRouteIds).containsExactly(routeId);

        // when
        final boolean initialized = initializer.initialize(routeId, day);

        // then
        assertThat(initialized).isTrue();

        // when
        final boolean initializedAgain = initializer.initialize(routeId, day);

        // then
        assertThat(initializedAgain).isFalse();
        assertThat(countsSpy.findCounts(routeId, modelId, day)).containsExactly(
            new SameDayFullOutcomeCount(0, 0, 0, 0, day.start()));
        verify(countsSpy, times(1)).countFromSource(routeId, modelId, day, day.end());
    }

    @Test
    void 초기화가_완료되기_전에는_일부_거리의_집계만_다른_트랜잭션에_보이지_않는다() throws Exception {
        // when
        committedSettlementWithoutCounts();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            call.callRealMethod();
            written.countDown();
            await(release);
            return null;
        }).when(countsSpy).upsertCounts(anyLong(), anyLong(), any(), any());
        Future<?> seed = workers.submit(() -> initializer.initialize(routeId, day));
        try {
            await(written);

            // then
            assertThat(count("same_day_model_full_outcomes")).isZero();
        } finally {
            release.countDown();
        }
        seed.get(5, TimeUnit.SECONDS);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void 초기화_중_품질_변경은_같은_노선_잠금으로_직렬화된다() throws Exception {
        // when
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
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

        // then
        assertThat(countsSpy.findCounts(routeId, modelId, day)).isEmpty();
        assertThat(initializer.initialize(routeId, day)).isTrue();
        assertThat(countsSpy.findCounts(routeId, modelId, day)).hasSize(1);
    }

    private void committedSettlementWithoutCounts() {
        savePending();
        initializeEmptyDay();
        settlement.settleArrivalLabels();
        jdbc.sql("TRUNCATE same_day_model_full_outcomes").update();
    }

    private void pauseSource(CountDownLatch read, CountDownLatch release) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                await(release);
            }
            return result;
        }).when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());
    }

    private void awaitLockWait() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (
                jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0)")
                    .query(Boolean.class)
                    .single()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("실제 PostgreSQL 잠금 대기에 도달하지 않았다");
    }

    private long total() {
        return jdbc.sql("SELECT coalesce(sum(row_count),0) FROM same_day_model_full_outcomes")
            .query(Long.class)
            .single();
    }
}
