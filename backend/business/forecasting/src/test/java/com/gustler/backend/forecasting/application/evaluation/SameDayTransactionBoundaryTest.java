package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.SameDayTransactionBoundaryTest.FixedClock;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.application.quality.DemandStatisticsRebuildTrigger;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuilder;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak.SeenToEnd;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Known;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle.Unknown;
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
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcEvaluationWorkStore;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트 전체를 @Transactional로 감싸지 않는다. 실제 작업 빈의 트랜잭션 프록시를 호출하고,
 * 다른 트랜잭션에서 커밋 전/후를 읽어 원본/대기 자료/집계의 원자성을 확인한다.
 * 관측/통계 조회와 모델 입력은 고정한다. 저장 SQL과 트랜잭션 관리자는 실제 PostgreSQL을 사용한다.
 * 검증 스레드의 각 조회는 자동 커밋으로 실행되어 작업 트랜잭션에 참여하지 않는다.
 */
@ForecastingIntegrationTest
@Import(FixedClock.class)
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
    @MockitoSpyBean
    JdbcEvaluationWorkStore workSpy;
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
        // DB를 비웠으므로 이전 테스트의 캐시도 정상 회차를 통해 정리한다.
        settlement.settleArrivalLabels();
        forecast = context.getBean(ForecastBatchWriter.class);
        workers = Executors.newFixedThreadPool(2);
        fixture();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt()))
            .thenReturn(List.of(arrival()));
        when(trajectories.readTrajectories(batchId)).thenReturn(List.of(new VehicleTrajectory(sourceId,
            new ObservedVehicle("fixture-vehicle", versionId, 1, OBSERVED_AT, 12, null),
            new Known(12), new SeatSlope.Known(0),
            new Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new SeenToEnd(0), 44)));
        when(statistics.readAsOf(anyLong(), any(), anyString(), any())).thenReturn(new StopDemandStatistics(
            versionId, ForecastTimeSlot.of(batch(), Clock.fixed(NOW, ZoneOffset.UTC)), 1, List.of()));
    }

    @AfterEach
    void 작업을_정리한다() throws InterruptedException {
        workers.shutdownNow();
        final boolean stopped = workers.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(stopped).as("테스트의 백그라운드 작업이 남지 않는다").isTrue();
    }

    @Test
    void 실제_DB에서_복원한_대기는_반복_평가해도_상세를_한번만_읽는다() {
        // given
        savePending();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        Mockito.clearInvocations(workSpy, arrivals);

        // when
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();

        // then
        Mockito.verify(workSpy).restore(versionId, List.of(sourceId));
        Mockito.verify(arrivals, Mockito.times(3))
            .findAfter(versionId, "fixture-vehicle", GENERATED_AT, 400);
        assertThat(count("forecast_evaluation_work")).isOne();
        assertThat(count("forecast_evaluation_pending")).isOne();
        assertThat(count("forecast_evaluation_result")).isZero();
    }

    @Test
    void 정산_커밋_전에는_원본과_대기자료와_당일성적이_다른_트랜잭션에_보이지_않는다() throws Exception {
        // when
        savePending();
        initializeEmptyDay();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            call.callRealMethod();
            written.countDown();
            await(release);
            return null;
        }).when(countsSpy).addCounts(anyLong(), anyLong(), any(), any());
        Future<?> running = workers.submit(settlement::settleArrivalLabels);
        try {
            await(written);

            // then
            assertUnsettled();
        } finally {
            release.countDown();
        }
        running.get(10, TimeUnit.SECONDS);
        assertSettledOnce();
    }

    @Test
    void 당일성적_저장_실패는_원본_정산과_대기자료도_롤백하고_재시도는_한번만_반영한다() {
        // when
        savePending();
        initializeEmptyDay();
        doAnswer(call -> {
            call.callRealMethod();
            timeout();
            return null;
        }).when(countsSpy).addCounts(anyLong(), anyLong(), any(), any());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(settlement::settleArrivalLabels);

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(QueryTimeoutException.class);
        assertUnsettled();

        doAnswer(call -> call.callRealMethod()).when(countsSpy).addCounts(anyLong(), anyLong(), any(), any());
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        assertSettledOnce();
    }

    @Test
    void 같은_예보를_동시에_정산해도_원본과_대기자료와_당일성적은_한번만_반영한다() throws Exception {
        // when
        savePending();
        initializeEmptyDay();
        CountDownLatch start = new CountDownLatch(1);
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of(arrival()));
        Future<?> first = workers.submit(() -> {
            await(start);
            settlement.settleArrivalLabels();
            return null;
        });
        Future<?> second = workers.submit(() -> {
            await(start);
            settlement.settleArrivalLabels();
            return null;
        });
        start.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        // then
        assertSettledOnce();
    }

    @Test
    void 예보_커밋_전에는_예보와_묶음_완료표시가_다른_트랜잭션에_보이지_않는다() throws Exception {
        // when
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            Object published = call.callRealMethod();
            written.countDown();
            await(release);
            return published;
        }).when(publications()).save(any());
        Future<?> running = workers.submit(this::writeForecast);
        try {
            await(written);

            // then
            assertNoForecast();
        } finally {
            release.countDown();
        }
        running.get(10, TimeUnit.SECONDS);
        assertForecastComplete();
    }

    @Test
    void 묶음_완료표시_후_실패하면_예보도_롤백하고_재시도는_중복없이_저장한다() {
        // when
        doAnswer(call -> {
            call.callRealMethod();
            timeout();
            return null;
        }).when(publications()).save(any());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(this::writeForecast);

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(QueryTimeoutException.class);
        assertNoForecast();
        doAnswer(call -> call.callRealMethod()).when(publications()).save(any());
        writeForecast();
        writeForecast();
        assertForecastComplete();
    }

    @Test
    void 별도_초기집계가_실패해도_예보와_묶음_완료는_커밋된다() {
        // when
        doAnswer(call -> {
            timeout();
            return call.callRealMethod();
        })
            .when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> initializer().initialize(routeId, SeoulDay.containing(NOW)));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(QueryTimeoutException.class);

        writeForecast();

        assertForecastComplete();
        assertThat(count("same_day_model_full_outcomes")).isZero();
    }

    @Test
    void 미초기화_중에도_정산과_통계_대기자료는_함께_커밋된다() {
        // given
        savePending();

        // when
        settlement.settleArrivalLabels();

        // then
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
            .param(sourceId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        assertThat(count("same_day_model_full_outcomes")).isZero();
    }

    @Test
    void 초기화_전후의_정산은_당일_합계에_정확히_한번씩_반영된다() {
        // when
        savePending();
        settlement.settleArrivalLabels();

        // then
        assertThat(count("same_day_model_full_outcomes")).isZero();
        assertThat(initializer().initialize(routeId, SeoulDay.containing(NOW))).isTrue();
        final long laterBatch = insertBatch(OBSERVED_AT.plusSeconds(2));
        final long later = insertObservation(laterBatch, 1, 12);
        publish(laterBatch, OBSERVED_AT.plusSeconds(2),
            new SeatForecast(later, versionId, 2, 1, modelId, 1, .25, .25, 12.5, GENERATED_AT));
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        assertThat(initializer().initialize(routeId, SeoulDay.containing(NOW))).isFalse();
        assertThat(countsSpy.findCounts(routeId, modelId, SeoulDay.containing(NOW))).singleElement()
            .extracting(SameDayFullOutcomeCount::rowCount).isEqualTo(2);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(2);
    }

    @Test
    void 도착_관측을_읽는_동안_같은_노선의_품질_잠금을_다른_트랜잭션이_사용할_수_있다() {
        // given
        savePending();
        ArrivalCandidate candidate = arrival();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager
                .isActualTransactionActive()).isFalse();
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(status -> context.getBean(RouteDataQualityAccess.class).lock(versionId));
            return List.of(candidate);
        });

        // when
        settlement.settleArrivalLabels();

        // then
        assertThat(count("forecast_evaluation_result")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
    }

    @Test
    void 품질_버전이_바뀌어도_다시_계산한_결과가_같으면_정산과_표본을_한번만_저장한다() {
        // given
        savePending();
        ArrivalCandidate candidate = arrival();
        AtomicBoolean changed = new AtomicBoolean();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            if (changed.compareAndSet(false, true)) {
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(
                    status -> {
                        context.getBean(RouteDataQualityAccess.class).lock(versionId);
                        jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
                            .param(routeId).update();
                    });
            }
            return List.of(candidate);
        });

        // when
        settlement.settleArrivalLabels();

        // then
        assertThat(count("forecast_evaluation_result")).isOne();
        assertThat(count("stop_demand_pending_sample")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of(candidate));
        settlement.settleArrivalLabels();
        assertThat(count("forecast_evaluation_result")).isOne();
        assertThat(count("stop_demand_pending_sample")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
    }

    @Test
    void 계산_중_정산_작업이_갱신되면_새_작업을_지우지_않고_다음_회차에_다시_읽는다() {
        // given
        savePending();
        ArrivalCandidate candidate = arrival();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                context.getBean(RouteDataQualityAccess.class).lock(versionId);
                workSpy.register(versionId, List.of(sourceId));
            });
            return List.of(candidate);
        });

        // when
        settlement.settleArrivalLabels();

        // then
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(count("forecast_evaluation_work")).isOne();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of(candidate));
        settlement.settleArrivalLabels();
        assertThat(count("forecast_evaluation_result")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
    }

    @Test
    void 다음_노선의_저장이_실패해도_앞서_커밋한_노선의_정산은_유지된다() {
        // given
        savePending();
        ArrivalCandidate firstArrival = arrival();
        OtherRoute other = otherRoute();
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            if ((long) call.getArgument(0) == versionId) {
                return List.of(firstArrival);
            }
            return List.of(other.arrival());
        });
        doAnswer(call -> {
            if ((long) call.getArgument(0) == other.version()) {
                throw new IllegalStateException("두 번째 노선 작업 종료 실패");
            }
            return call.callRealMethod();
        }).when(workStore()).releaseCompleted(anyLong(), any());

        // when
        assertThatThrownBy(settlement::settleArrivalLabels)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("두 번째 노선 작업 종료 실패");

        // then
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
                .param(sourceId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
                .param(other.source()).query(String.class).single()).isEqualTo("PENDING");
        assertThat(count("stop_demand_pending_sample")).isOne();
        assertThat(count("forecast_evaluation_work")).isOne();
        doAnswer(call -> call.callRealMethod()).when(workStore()).releaseCompleted(anyLong(), any());
        settlement.settleArrivalLabels();
        assertThat(count("forecast_evaluation_result")).isEqualTo(2);
        assertThat(count("stop_demand_pending_sample")).isEqualTo(2);
        assertThat(count("forecast_evaluation_work")).isZero();
    }

    @Test
    void 외부_트랜잭션으로_여러_노선의_정산을_묶으려_하면_실행하지_않는다() {
        // given
        savePending();
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        // when
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> settlement.settleArrivalLabels()))
            .isInstanceOf(IllegalTransactionStateException.class);

        // then
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(count("forecast_evaluation_work")).isOne();
    }

    private JdbcEvaluationWorkStore workStore() {
        return AopTestUtils.getUltimateTargetObject(workSpy);
    }

    private OtherRoute otherRoute() {
        final long firstRoute = routeId;
        final long firstVersion = versionId;
        final long firstBatch = batchId;
        final long firstSource = sourceId;
        final long firstArrival = arrivalId;
        try {
            routeId = jdbc.sql("""
                INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                VALUES ('other','fixture','other','other','start','end') RETURNING id
                """).query(Long.class).single();
            jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?)").param(routeId).update();
            versionId = jdbc.sql("""
                INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence)
                VALUES (?,?,?,20) RETURNING id
                """).params(routeId, "1".repeat(64), OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC))
                .query(Long.class).single();
            for (final int stop : List.of(1, 2)) {
                jdbc.sql("INSERT INTO route_stop VALUES (?, ?, ?, ?, 'UP', true)")
                    .params(versionId, stop, "stop-" + stop, "stop-" + stop).update();
            }
            batchId = insertBatch(OBSERVED_AT);
            sourceId = insertObservation(batchId, 1, 12);
            arrivalId = insertObservation(insertBatch(ARRIVED_AT), 2, 0);
            savePending();
            return new OtherRoute(versionId, sourceId, arrival());
        } finally {
            routeId = firstRoute;
            versionId = firstVersion;
            batchId = firstBatch;
            sourceId = firstSource;
            arrivalId = firstArrival;
        }
    }

    private record OtherRoute(long version, long source, ArrivalCandidate arrival) {
    }

    SameDayFullOutcomesInitializer initializer() {
        return context.getBean(SameDayFullOutcomesInitializer.class);
    }

    void assertUnsettled() {
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
                .param(sourceId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(count("stop_demand_pending_sample")).isZero();
        assertThat(countsSpy.findCounts(routeId, modelId, SeoulDay.containing(ARRIVED_AT))).containsExactly(
            new SameDayFullOutcomeCount(0, 0, 0, 0, SeoulDay.containing(ARRIVED_AT).start()));
    }

    void assertSettledOnce() {
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id=?")
                .param(sourceId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(count("stop_demand_pending_sample")).isEqualTo(1);
        assertThat(countsSpy.findCounts(routeId, modelId, SeoulDay.containing(ARRIVED_AT))
                .stream()
                .filter(row -> row.rowCount() > 0)
                .toList()).containsExactly(
                    new SameDayFullOutcomeCount(1, 1, 1, .25, ARRIVED_AT));
    }

    @Test
    void 전체_완료와_차량_재시작은_함께_커밋된다() throws Exception {
        // when
        savePending();
        settlement.settleArrivalLabels();
        DemandStatisticsRebuildTrigger quality = context.getBean(DemandStatisticsRebuildTrigger.class);
        DemandStatisticsRebuilder rebuild = context.getBean(DemandStatisticsRebuilder.class);
        quality.requestVehicle(versionId, "fixture-vehicle");
        rebuild.step(versionId, "fixture-vehicle");
        UUID before = vehicleRebuildRequest();
        quality.requestRoute(versionId);
        for (int i = 0; i < 100; i++) {
            String phase =
                jdbc.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=''")
                    .param(versionId)
                    .query(String.class)
                    .optional()
                    .orElse("");
            final long totals =
                jdbc.sql(
                    "SELECT count(*) FROM stop_demand_rebuild_total WHERE route_version_id=? AND scope_vehicle_id=''")
                    .param(versionId)
                    .query(Long.class)
                    .single();
            if (phase.equals("CLEAN") && totals == 0) {
                break;
            }

            // then
            assertThat(rebuild.step(versionId, "")).isTrue();
        }
        assertThat(jdbc.sql("SELECT phase FROM stop_demand_rebuild_progress WHERE route_version_id=? AND vehicle_id=''")
                        .param(versionId).query(String.class).single()).isEqualTo("CLEAN");
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        Future<?> running = workers.submit(() -> tx.executeWithoutResult(status -> {
            rebuild.step(versionId, "");
            staged.countDown();
            try {
                await(release);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("완료 저장 후 실패 주입");
        }));
        try {
            await(staged);
            // 다른 트랜잭션에는 전체 완료도 새 차량 요청 번호도 아직 보이지 않는다.
            assertThat(globalRebuildRequests()).isEqualTo(1);
            assertThat(vehicleRebuildRequest()).isEqualTo(before);
        } finally {
            release.countDown();
        }

        // when
        Throwable phaseFailure1 = catchThrowable(() -> running.get(10, TimeUnit.SECONDS));

        // then
        assertThat(phaseFailure1)
            .isNotNull().hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(globalRebuildRequests()).isEqualTo(1);
        assertThat(vehicleRebuildRequest()).isEqualTo(before);

        rebuild.step(versionId, "");

        assertThat(globalRebuildRequests()).isZero();
        assertThat(vehicleRebuildRequest()).isNotEqualTo(before);
    }

    private long globalRebuildRequests() {
        return jdbc.sql("SELECT count(*) FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id=''")
            .param(versionId).query(Long.class).single();
    }

    private UUID vehicleRebuildRequest() {
        return jdbc.sql(
        "SELECT request_id FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id='fixture-vehicle'")
            .param(versionId)
            .query(UUID.class)
            .single();
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

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void 상세를_생략한_평가도_보정에_한번만_반영하고_집계를_다시_만들_수_있다() {
        // given
        saveCompactPending();
        initializeEmptyDay();

        // when
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();

        // then
        assertCompactSettledOnce();
        assertThat(count("forecast_evaluation_pending")).isZero();
        assertThat(count("forecast_evaluation_work")).isZero();
        jdbc.sql("TRUNCATE same_day_model_full_outcomes").update();
        initializer().initialize(routeId, SeoulDay.containing(ARRIVED_AT));
        assertCompactSettledOnce();
    }

    @Test
    void 상세를_생략한_평가도_초기화_전_정산을_집계에서_빠뜨리지_않는다() {
        // given
        saveCompactPending();

        // when
        settlement.settleArrivalLabels();
        initializer().initialize(routeId, SeoulDay.containing(ARRIVED_AT));

        // then
        assertCompactSettledOnce();
    }

    @Test
    void 보정_저장에_실패하면_최소_결과도_롤백하고_재시도는_한번만_반영한다() {
        // given
        saveCompactPending();
        initializeEmptyDay();
        doAnswer(call -> {
            call.callRealMethod();
            timeout();
            return null;
        })
            .when(countsSpy).addCounts(anyLong(), anyLong(), any(), any());

        // when
        assertThatThrownBy(settlement::settleArrivalLabels).isInstanceOf(QueryTimeoutException.class);

        // then
        assertThat(count("forecast_evaluation_compact")).isZero();
        assertUnsettled();
        doAnswer(call -> call.callRealMethod()).when(countsSpy).addCounts(anyLong(), anyLong(), any(), any());
        settlement.settleArrivalLabels();
        settlement.settleArrivalLabels();
        assertCompactSettledOnce();
    }

    @Test
    void 한_정류장_앞은_상세_생략_표시가_있어도_상세와_승하차_자료를_보존한다() {
        // given
        savePending();
        jdbc.sql("UPDATE seat_forecast SET evaluation_detail_required=false").update();
        initializeEmptyDay();

        // when
        settlement.settleArrivalLabels();

        // then
        assertSettledOnce();
        assertThat(count("forecast_evaluation_result")).isOne();
        assertThat(count("forecast_evaluation_compact")).isZero();
    }

    @Test
    void 최소_결과도_품질이_바뀐_예보를_다시_집계하지_않는다() {
        // given
        saveCompactPending();
        settlement.settleArrivalLabels();
        jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?")
            .param(routeId).update();

        // when
        initializer().initialize(routeId, SeoulDay.containing(ARRIVED_AT));

        // then
        assertThat(countsSpy.findCounts(routeId, modelId, SeoulDay.containing(ARRIVED_AT)))
            .allMatch(row -> row.rowCount() == 0);
        assertThat(count("forecast_evaluation_compact")).isOne();
    }

    @Test
    void 최소_결과를_동시에_저장해도_보정은_한번만_증가한다() throws Exception {
        // given
        saveCompactPending();
        initializeEmptyDay();
        ForecastEvaluationWriter writer = context.getBean(ForecastEvaluationWriter.class);
        ForecastEvaluation completed = ForecastEvaluation.completed(sourceId, 2,
            new Settled(arrivalId, 0), NOW);
        CountDownLatch start = new CountDownLatch(1);

        // when
        Future<?> first = workers.submit(() -> {
            await(start);
            return writer.complete(List.of(completed));
        });
        Future<?> second = workers.submit(() -> {
            await(start);
            return writer.complete(List.of(completed));
        });
        start.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        // then
        assertCompactSettledOnce();
    }

    @Test
    void 최소_결과는_도착_당시의_편도_식별값을_유지한다() {
        // given
        saveCompactPending();
        String tripAtSettlement = jdbc.sql("SELECT vehicle_trip_key FROM forecast_eligible_observation WHERE id=?")
            .param(arrivalId).query(String.class).single();

        // when
        settlement.settleArrivalLabels();
        jdbc.sql("DELETE FROM observation_trip_assignment WHERE observation_id=?").param(arrivalId).update();
        jdbc.sql("UPDATE vehicle_observation SET vehicle_trip_key='changed-after-settlement' WHERE id=?")
            .param(arrivalId).update();

        // then
        assertThat(
            jdbc.sql("SELECT arrival_vehicle_trip_key FROM forecast_evaluation_compact WHERE vehicle_observation_id=?")
                .param(sourceId)
                .query(String.class)
                .single()).isEqualTo(tripAtSettlement);
        assertThat(jdbc.sql("SELECT arrival_vehicle_trip_key FROM forecast_evaluation WHERE vehicle_observation_id=?")
                .param(sourceId).query(String.class).single()).isEqualTo(tripAtSettlement);
    }

    @Test
    void 최소_결과를_저장한_예보는_재등록해도_대기로_되돌아가지_않는다() {
        // given
        saveCompactPending();
        initializeEmptyDay();
        settlement.settleArrivalLabels();

        // when
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
            workSpy.register(versionId, List.of(sourceId)));

        // then
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(count("forecast_evaluation_pending")).isZero();
        assertCompactSettledOnce();
    }

    void saveCompactPending() {
        savePending();
        jdbc.sql("UPDATE seat_forecast SET stops_to_target=2, evaluation_detail_required=false").update();
    }

    void assertCompactSettledOnce() {
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(count("forecast_evaluation_compact")).isOne();
        assertThat(count("stop_demand_pending_sample")).isZero();
        assertThat(countsSpy.findCounts(routeId, modelId, SeoulDay.containing(ARRIVED_AT))
                .stream()
                .filter(row -> row.rowCount() > 0)
                .toList()).containsExactly(
                    new SameDayFullOutcomeCount(2, 1, 1, .25, ARRIVED_AT));
    }

    void initializeEmptyDay() {
        SeoulDay day = SeoulDay.containing(ARRIVED_AT);
        countsSpy.upsertCounts(routeId, modelId, day, List.of(new SameDayFullOutcomeCount(0, 0, 0, 0, day.start())));
    }

    void savePending() {
        publish(batchId, OBSERVED_AT, new SeatForecast(
            sourceId, versionId, 2, 1, modelId, 1, .25, .25, 12.5, GENERATED_AT));
    }

    JdbcForecastPublicationRepository publications() {
        return AopTestUtils.getUltimateTargetObject(publicationsSpy);
    }

    void publish(final long batch, Instant observedAt, SeatForecast prediction) {
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
                new SupportedForecastScope(List.of("3330")),
                input -> new SeatForecastResult(new SeatDistribution(List.of(.4, .6)), .4), OBSERVED_AT));
    }

    PendingForecastBatch batch() {
        return new PendingForecastBatch(batchId, versionId, routeId, OBSERVED_AT);
    }

    ArrivalCandidate arrival() {
        final long direction = jdbc.sql("SELECT quality_direction FROM forecast_observation_quality WHERE id=?")
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
        versionId =
            jdbc.sql(
            "INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence) VALUES (?,? ,?,20) RETURNING id")
                .params(routeId, "0".repeat(64), OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC))
                .query(Long.class)
                .single();
        for (final int stop : List.of(1, 2)) {
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
        final long arrivalBatch = insertBatch(ARRIVED_AT);
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

    long insertObservation(final long batch, final int stop, final int seats) {
        final long id = jdbc.sql("""
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
