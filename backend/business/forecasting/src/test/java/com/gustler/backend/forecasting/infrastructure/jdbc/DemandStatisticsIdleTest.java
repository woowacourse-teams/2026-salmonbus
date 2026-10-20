package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsRebuilder;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline.Step;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline.Step.Status;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 외부 테스트 트랜잭션 없이 완료 통계의 상태 조회와 실제 품질 행 잠금을 검증한다. */
@ForecastingIntegrationTest
@TestPropertySource(properties = "forecast.statistics-interval=6h")
class DemandStatisticsIdleTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant DATA_UNTIL = NOW.minusSeconds(60);
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DemandStatisticsPipeline pipeline;
    @Autowired
    DemandStatisticsRunRepository runs;
    @Autowired
    PlatformTransactionManager transactions;
    @MockitoSpyBean
    JdbcDemandStatisticsStore store;
    @MockitoSpyBean
    RouteDataQualityAccess quality;
    @MockitoBean
    ArchivedDemandStatisticsRebuilder archived;
    @MockitoBean
    Clock clock;
    private long route;
    private long version;

    @BeforeEach
    void 초기_집계와_통계_발행을_마친_노선을_준비한다() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        String key = UUID.randomUUID().toString().substring(0, 30);
        route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES (?, 'test', ?, 'test', 'start', 'end') RETURNING id
            """).params(key, key).query(Long.class).single();
        version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES (?, ?, ?) RETURNING id
            """).params(route, "0".repeat(64), at(DATA_UNTIL)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?)").param(route).update();
        jdbc.sql("INSERT INTO stop_demand_baseline(route_version_id,initialized,data_until) VALUES (?,true,?)")
            .params(version, at(DATA_UNTIL)).update();
        jdbc.sql("""
            INSERT INTO stop_demand_run(route_version_id,run_id,quality_revision,phase,data_until,completed_at)
            VALUES (?,?,1,'DONE',?,?)
            """).params(version, UUID.randomUUID(), at(DATA_UNTIL), at(NOW)).update();
    }

    @AfterEach
    void 이_테스트의_노선과_집계_상태만_정리한다() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM stop_demand_rebuild_request WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM stop_demand_run WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM stop_demand_baseline WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
            jdbc.sql("DELETE FROM route_data_quality WHERE route_id=?").param(route).update();
            jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
        });
    }

    @Test
    void 갱신할_일이_없으면_품질_행을_잠그거나_S3_재집계를_준비하지_않는다() {
        // given
        UUID runId = runs.find(version).orElseThrow().runId();

        // when
        Step step = pipeline.step(version);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(step.status()).isEqualTo(Status.IDLE);
            softly.assertThat(step.dataUntil()).isEqualTo(DATA_UNTIL);
        }
        assertThat(runs.find(version).orElseThrow().runId()).isEqualTo(runId);
        verify(quality, never()).lock(anyLong());
        verify(store, never()).baseline(anyLong());
        verifyNoInteractions(archived);
    }

    @Test
    void 다른_작업이_품질_행을_잠그고_있어도_할_일_없는_집계는_종료한다() throws InterruptedException {
        // given
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(version);

                // when
                Future<Step> running = worker.submit(() -> pipeline.step(version));

                // then
                try {
                    assertThat(running.get(2, TimeUnit.SECONDS).status())
                        .isEqualTo(Status.IDLE);
                } catch (Exception failure) {
                    throw new AssertionError("유휴 집계가 다른 트랜잭션의 품질 잠금을 기다렸다", failure);
                }
            });
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"진행중", "품질변경", "차량재집계", "전체재집계", "초기집계미완료", "초기집계기록없음"})
    void 작업이_남아_있으면_최근에_발행했어도_유휴로_판단하지_않는다(String condition) {
        // given
        switch (condition) {
            case "진행중" -> jdbc.sql("UPDATE stop_demand_run SET phase='FOLD' WHERE route_version_id=?")
                .param(version)
                .update();
            case "품질변경" -> jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?")
                .param(route)
                .update();
            case "차량재집계", "전체재집계" -> jdbc.sql("""
                INSERT INTO stop_demand_rebuild_request(route_version_id,vehicle_id,request_id) VALUES (?,?,?)
                """).params(version, vehicleScopeFor(condition), UUID.randomUUID()).update();
            case "초기집계미완료" -> jdbc.sql("UPDATE stop_demand_baseline SET initialized=false WHERE route_version_id=?")
                .param(version)
                .update();
            case "초기집계기록없음" -> jdbc.sql("DELETE FROM stop_demand_baseline WHERE route_version_id=?")
                .param(version)
                .update();
            default -> throw new AssertionError(condition);
        }

        // when
        Optional<Instant> idle = store.idleDataUntil(version, NOW.minus(Duration.ofHours(6)));

        // then
        assertThat(idle).isEmpty();
    }

    @Test
    void 갱신_주기가_되는_정확한_시각부터_집계를_다시_시작한다() {
        // given
        jdbc.sql("UPDATE stop_demand_run SET completed_at=? WHERE route_version_id=?")
            .params(at(NOW.minus(Duration.ofHours(6))), version).update();

        // when
        Step step = pipeline.step(version);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(step.status()).isEqualTo(Status.PROGRESSED);
            softly.assertThat(step.phase()).isEqualTo("CLEAN");
        }
        verify(quality).lock(version);
    }

    @Test
    void 사전_조회_후_다른_작업이_집계를_완료하면_잠금_획득_후_재확인하고_종료한다() {
        // given
        jdbc.sql("UPDATE stop_demand_run SET completed_at=? WHERE route_version_id=?")
            .params(at(NOW.minus(Duration.ofHours(6))), version).update();
        UUID runId = runs.find(version).orElseThrow().runId();
        doAnswer(call -> {
            Object stale = call.callRealMethod();
            TransactionTemplate other = new TransactionTemplate(transactions);
            other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(status ->
                jdbc.sql("UPDATE stop_demand_run SET completed_at=? WHERE route_version_id=?")
                    .params(at(NOW), version).update());
            return stale;
        }).when(store).idleDataUntil(anyLong(), any());

        // when
        Step step = pipeline.step(version);

        // then
        assertThat(step.status()).isEqualTo(Status.IDLE);
        assertThat(runs.find(version).orElseThrow().runId()).isEqualTo(runId);
        verify(quality).lock(version);
    }

    @Test
    void 유휴_판단_직후_들어온_재집계_요청은_지우지_않고_다음_조회에서_발견한다() {
        // given
        assertThat(pipeline.step(version).status()).isEqualTo(Status.IDLE);
        jdbc.sql("INSERT INTO stop_demand_rebuild_request(route_version_id,vehicle_id,request_id) VALUES (?,'bus',?)")
            .params(version, UUID.randomUUID()).update();

        // when
        Optional<Instant> idle = store.idleDataUntil(version, NOW.minus(Duration.ofHours(6)));

        // then
        assertThat(idle).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_rebuild_request WHERE route_version_id=?")
            .param(version).query(Long.class).single()).isOne();
    }

    private static OffsetDateTime at(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static String vehicleScopeFor(String condition) {
        if (condition.equals("전체재집계")) {
            return "";
        }
        return "bus";
    }
}
