package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.application.quality.DemandStatisticsRebuildTrigger;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuilder;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcForecastEvaluationRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcForecastPublicationRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcSameDayFullOutcomesStore;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트 전체를 @Transactional로 감싸지 않는다. 실제 작업 빈의 트랜잭션 프록시를 호출하고,
 * 다른 트랜잭션에서 커밋 전/후를 읽어 원본/대기 자료/집계의 원자성을 확인한다.
 * 관측/통계 조회와 모델 입력은 고정한다. 저장 SQL과 트랜잭션 관리자는 실제 PostgreSQL을 사용한다.
 * 검증 스레드의 각 조회는 자동 커밋으로 실행되어 작업 트랜잭션에 참여하지 않는다.
 */
@ForecastingIntegrationTest
@Import(SameDayTransactionBoundaryTest.FixedClock.class)
@TestPropertySource(properties = {
    "spring.datasource.hikari.data-source-properties.options=-c statement_timeout=5000 -c lock_timeout=2000",
    "forecast.same-day-initialization.retry-interval=60s",
    "forecast.same-day-initialization.statement-timeout=500ms",
    "forecast.same-day-initialization.lock-timeout=100ms"
})
class SameDayTransactionBoundaryTest {
    static final Instant OBSERVED_AT = Instant.parse("2026-09-28T00:00:00Z");
    static final Instant GENERATED_AT = OBSERVED_AT.plusSeconds(1);
    static final Instant ARRIVED_AT = OBSERVED_AT.plusSeconds(30);
    static final Instant NOW = OBSERVED_AT.plusSeconds(60);
    @Autowired
    ApplicationContext context;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    JdbcSameDayFullOutcomesStore countsSpy;
    @MockitoSpyBean
    JdbcForecastPublicationRepository publicationsSpy;
    @MockitoSpyBean
    JdbcForecastEvaluationRepository evaluationsSpy;
    @MockitoBean
    ArrivalObservationQuery arrivals;
    @MockitoBean
    VehicleTrajectoryQuery trajectories;
    @MockitoBean
    StopDemandStatisticsRepository statistics;
    EvaluateForecastsService settlement;
    ForecastBatchWriter forecast;
    ExecutorService workers;
    long routeId;
    long versionId;
    long batchId;
    long sourceId;
    long arrivalId;
    long modelId;

    @BeforeEach
    void 작업_빈에_실제_트랜잭션_프록시와_독립된_관측을_준비한다() {
        jdbc.sql("TRUNCATE route, model_deployment RESTART IDENTITY CASCADE").update();
        settlement = context.getBean(EvaluateForecastsService.class);
        forecast = context.getBean(ForecastBatchWriter.class);
        workers = Executors.newFixedThreadPool(2);
        fixture();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt()))
            .thenReturn(List.of(arrival()));
        when(trajectories.readTrajectories(batchId)).thenReturn(List.of(new VehicleTrajectory(sourceId,
            new ObservedVehicle("fixture-vehicle", versionId, 1, OBSERVED_AT, 12, null),
            new ObservedSeats.Known(12), new SeatSlope.Known(0),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new FullSeatStreak.SeenToEnd(0), 44)));
        when(statistics.readAsOf(anyLong(), any(), anyString(), any())).thenReturn(new StopDemandStatistics(
            versionId, ForecastTimeSlot.of(batch(), Clock.fixed(NOW, ZoneOffset.UTC)), 1, List.of()));
    }

    @AfterEach
    void 작업을_정리한다() throws InterruptedException {
        workers.shutdownNow();
        boolean stopped = workers.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(stopped).as("테스트의 백그라운드 작업이 남지 않는다").isTrue();
    }

    @Test
    void 정산_커밋_전에는_원본과_대기자료와_당일성적이_다른_트랜잭션에_보이지_않는다() throws Exception {
        savePending();
        initializeEmptyDay();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            call.callRealMethod();
            written.countDown();
            await(release);
            return null;
        }).when(countsSpy).add(any());
        Future<?> running = workers.submit(settlement::settleArrivalLabels);
        try {
            await(written);
            assertUnsettled();
        } finally {
            release.countDown();
        }
        running.get(10, TimeUnit.SECONDS);
        assertSettledOnce();
    }

    @Test
    void 당일성적_저장_실패는_원본_정산과_대기자료도_롤백하고_재시도는_한번만_반영한다() {
        savePending();
        initializeEmptyDay();
        doAnswer(call -> {
            call.callRealMethod();
            timeout();
            return null;
        }).when(countsSpy).add(any());

        assertThatThrownBy(settlement::settleArrivalLabels).isInstanceOf(QueryTimeoutException.class);
        assertUnsettled();

        doAnswer(call -> call.callRealMethod()).when(countsSpy).add(any());
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        assertSettledOnce();
    }

    @Test
    void 같은_예보를_동시에_정산해도_원본과_대기자료와_당일성적은_한번만_반영한다() throws Exception {
        savePending();
        initializeEmptyDay();
        var bothReadPending = new CyclicBarrier(2);
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            bothReadPending.await(5, TimeUnit.SECONDS);
            return List.of(arrival());
        });
        Future<?> first = workers.submit(settlement::settleArrivalLabels);
        Future<?> second = workers.submit(settlement::settleArrivalLabels);
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertSettledOnce();
    }

    @Test
    void 예보_커밋_전에는_예보와_묶음_완료표시가_다른_트랜잭션에_보이지_않는다() throws Exception {
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object published = call.callRealMethod();
            written.countDown();
            await(release);
            return published;
        }).when(publications()).save(any());
        Future<?> running = workers.submit(this::writeForecast);
        try {
            await(written);
            assertNoForecast();
        } finally {
            release.countDown();
        }
        running.get(10, TimeUnit.SECONDS);
        assertForecastComplete();
    }

    @Test
    void 묶음_완료표시_후_실패하면_예보도_롤백하고_재시도는_중복없이_저장한다() {
        doAnswer(call -> {
            call.callRealMethod();
            timeout();
            return null;
        }).when(publications()).save(any());
        assertThatThrownBy(this::writeForecast).isInstanceOf(QueryTimeoutException.class);
        assertNoForecast();
        doAnswer(call -> call.callRealMethod()).when(publications()).save(any());
        writeForecast();
        writeForecast();
        assertForecastComplete();
    }

    @Test
    void 별도_초기집계가_실패해도_예보와_묶음_완료는_커밋된다() {
        doAnswer(call -> { timeout(); return call.callRealMethod(); })
            .when(countsSpy).countFromSource(anyLong(), any(), any());
        assertThatThrownBy(() -> initializer().initialize(routeId, SeoulDay.containing(NOW)))
            .isInstanceOf(QueryTimeoutException.class);

        writeForecast();

        assertForecastComplete();
        assertThat(count("same_day_full_outcomes")).isZero();
    }

    @Test
    void 미초기화_중에도_정산과_통계_대기자료는_함께_커밋된다() {
        savePending();

        settlement.settleArrivalLabels();

        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
            .param(sourceId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        assertThat(count("same_day_full_outcomes")).isZero();
    }

    @Test
    void 초기화_전후의_정산은_당일_합계에_정확히_한번씩_반영된다() {
        savePending();
        settlement.settleArrivalLabels();
        assertThat(count("same_day_full_outcomes")).isZero();
        assertThat(initializer().initialize(routeId, SeoulDay.containing(NOW))).isTrue();
        long laterBatch = insertBatch(OBSERVED_AT.plusSeconds(2));
        long later = insertObservation(laterBatch, 1, 12);
        publish(laterBatch, OBSERVED_AT.plusSeconds(2),
            new SeatForecast(later, versionId, 2, 1, modelId, 1, .25, .25, 12.5, GENERATED_AT));
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        assertThat(initializer().initialize(routeId, SeoulDay.containing(NOW))).isFalse();
        assertThat(countsSpy.findCounts(routeId, SeoulDay.containing(NOW))).singleElement()
            .extracting(SameDayFullOutcomeCount::rowCount).isEqualTo(2);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(2);
    }

    SameDayFullOutcomesInitializer initializer() {
        return context.getBean(SameDayFullOutcomesInitializer.class);
    }

    void assertUnsettled() {
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
            .param(sourceId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(count("stop_demand_pending_sample")).isZero();
        assertThat(countsSpy.findCounts(routeId, SeoulDay.containing(ARRIVED_AT))).containsExactly(
            new SameDayFullOutcomeCount(0, 0, 0, 0, SeoulDay.containing(ARRIVED_AT).start()));
    }

    void assertSettledOnce() {
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
            .param(sourceId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        assertThat(countsSpy.findCounts(routeId, SeoulDay.containing(ARRIVED_AT)).stream()
            .filter(row -> row.rowCount() > 0).toList()).containsExactly(
            new SameDayFullOutcomeCount(1, 1, 1, .25, ARRIVED_AT));
    }

    @Test
    void 전체_완료와_차량_재시작은_함께_커밋된다() throws Exception {
        savePending();
        settlement.settleArrivalLabels();
        var quality = context.getBean(DemandStatisticsRebuildTrigger.class);
        var rebuild = context.getBean(DemandStatisticsRebuilder.class);
        quality.requestVehicle(versionId,"fixture-vehicle");
        rebuild.step(versionId,"fixture-vehicle");
        UUID before = vehicleRebuildRequest();
        quality.requestRoute(versionId);
        for (int i=0; i<100; i++) {
            String phase = jdbc.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=''")
                .param(versionId).query(String.class).optional().orElse("");
            long totals = jdbc.sql("SELECT count(*) FROM stop_demand_rebuild_total WHERE route_version_id=? AND scope_vehicle_id=''")
                .param(versionId).query(Long.class).single();
            if (phase.equals("CLEAN") && totals==0) { break; }
            assertThat(rebuild.step(versionId,"")).isTrue();
        }
        assertThat(jdbc.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=''")
            .param(versionId).query(String.class).single()).isEqualTo("CLEAN");
        var staged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        Future<?> running = workers.submit(() -> tx.executeWithoutResult(status -> {
            rebuild.step(versionId,"");
            staged.countDown();
            try { await(release); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            throw new IllegalStateException("완료 저장 후 실패 주입");
        }));
        try {
            await(staged);
            // 다른 트랜잭션에는 전체 완료도 새 차량 요청 번호도 아직 보이지 않는다.
            assertThat(globalRebuildRequests()).isEqualTo(1);
            assertThat(vehicleRebuildRequest()).isEqualTo(before);
        } finally { release.countDown(); }
        assertThatThrownBy(() -> running.get(10,TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(globalRebuildRequests()).isEqualTo(1);
        assertThat(vehicleRebuildRequest()).isEqualTo(before);

        rebuild.step(versionId,"");

        assertThat(globalRebuildRequests()).isZero();
        assertThat(vehicleRebuildRequest()).isNotEqualTo(before);
    }

    private long globalRebuildRequests() {
        return jdbc.sql("SELECT count(*) FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id=''")
            .param(versionId).query(Long.class).single();
    }

    private UUID vehicleRebuildRequest() {
        return jdbc.sql("SELECT request_id FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id='fixture-vehicle'")
            .param(versionId).query(UUID.class).single();
    }

    void assertNoForecast() {
        assertThat(count("seat_forecast")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_publication WHERE source_batch_id=?")
            .param(batchId).query(Long.class).single()).isZero();
    }

    void assertForecastComplete() {
        assertThat(count("seat_forecast")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_publication WHERE source_batch_id=?")
            .param(batchId).query(Long.class).single()).isEqualTo(1);
    }

    long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single(); }

    void initializeEmptyDay() {
        var day = SeoulDay.containing(ARRIVED_AT);
        countsSpy.upsertCounts(routeId, day, List.of(new SameDayFullOutcomeCount(0, 0, 0, 0, day.start())));
    }

    void savePending() {
        publish(batchId, OBSERVED_AT, new SeatForecast(
            sourceId, versionId, 2, 1, modelId, 1, .25, .25, 12.5, GENERATED_AT));
    }

    JdbcForecastPublicationRepository publications() {
        return AopTestUtils.getUltimateTargetObject(publicationsSpy);
    }

    void publish(long batch, Instant observedAt, SeatForecast prediction) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            publicationsSpy.save(new ForecastPublication(batch, versionId, modelId, 1,
                context.getBean(RouteDataQualityAccess.class).lock(versionId), observedAt,
                prediction.generatedAt(), prediction.generatedAt(), List.of(prediction)));
            evaluationsSpy.addPending(versionId, List.of(
                ForecastEvaluation.pending(prediction.vehicleObservationId(), prediction.targetStopOrder())));
        });
    }

    void writeForecast() {
        forecast.writeForecastsOf(batch(), new RouteStops(versionId, "fixture", List.of(
            new RouteStop(versionId, 1, "stop-1", true), new RouteStop(versionId, 2, "stop-2", true))),
            new RuntimeSnapshot(new ActiveModelDeployment(modelId, "feature-v1", "fixture", "0".repeat(64)),
                new SupportedForecastScope(List.of("3330")), input -> new SeatForecastResult(new SeatDistribution(List.of(.4, .6)), .4), OBSERVED_AT));
    }

    PendingForecastBatch batch() { return new PendingForecastBatch(batchId, versionId, routeId, OBSERVED_AT); }

    ArrivalCandidate arrival() {
        long direction = jdbc.sql("SELECT quality_direction FROM forecast_observation_quality WHERE id=?")
            .param(arrivalId).query(Long.class).single();
        return new ArrivalCandidate(arrivalId,
            new ObservedVehicle("fixture-vehicle", versionId, 2, ARRIVED_AT, 0, null), direction, true);
    }

    void timeout() {
        jdbc.sql("SET LOCAL statement_timeout='50ms'").update();
        jdbc.sql("SELECT pg_sleep(0.2)").query().singleRow();
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("동시 실행의 지정된 경계에 도달한다").isTrue();
    }

    void fixture() {
        routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('fixture','fixture','fixture','fixture','start','end') RETURNING id
            """).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?)").param(routeId).update();
        versionId = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence) VALUES (?,? ,?,20) RETURNING id")
            .params(routeId, "0".repeat(64), OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC)).query(Long.class).single();
        for (int stop : List.of(1, 2)) {
            jdbc.sql("INSERT INTO route_stop VALUES (?, ?, ?, ?, 'UP', true)")
                .params(versionId, stop, "stop-" + stop, "stop-" + stop).update();
        }
        modelId = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES ('00000000-0000-0000-0000-000000000001','fixture','fixture','fixture',?,
                'fixture','fixture',?,?,'ACTIVE') RETURNING id
            """).params("0".repeat(64), "0".repeat(64), OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC))
            .query(Long.class).single();
        batchId = insertBatch(OBSERVED_AT);
        long arrivalBatch = insertBatch(ARRIVED_AT);
        sourceId = insertObservation(batchId, 1, 12);
        arrivalId = insertObservation(arrivalBatch, 2, 0);
    }

    long insertBatch(Instant at) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES (?,?,1,?,?,'SUCCESS_ROWS','fixture','fixture') RETURNING id
            """).params(versionId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), at.toString(),
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).query(Long.class).single();
    }

    long insertObservation(long batch, int stop, int seats) {
        long id = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES (?,?,1,'fixture-vehicle',?,?,?,2,?) RETURNING id
            """).params(batch, versionId, stop, "stop-" + stop, stop, seats).query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock sameDayBoundaryClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
