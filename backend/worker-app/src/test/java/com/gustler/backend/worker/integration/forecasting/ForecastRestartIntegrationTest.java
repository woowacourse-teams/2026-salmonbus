package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.TestDatabase;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import com.gustler.backend.worker.scheduling.ForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.SmartContextLoader;

/**
 * 예보를 켠 worker를 실제로 띄워, 실행 줄에 일이 쌓인 채로 닫았다가 같은 DB로 다시 띄운다. 닫을 때는 돌던 배치만 DB 연결 풀이 닫히기
 * 전에 끝내고 기다리던 일은 버리는지, 다시 뜬 뒤에는 남은 배치를 줍기가 찾아 처리하고 채점 결과가 끊기지 않는지를 본다. 비교 기준은
 * 같은 흐름을 한 번도 끊지 않고 처리한 노선이다.
 *
 * <p>닫힌 뒤의 DB를 봐야 하고 같은 DB로 두 번 띄워야 하므로 DB와 컨텍스트를 시험이 직접 만든다. 컨텍스트가 만든 시험 DB는 컨텍스트가
 * 닫힐 때 지워진다. 설정은 다른 통합 시험과 같은 방식으로 읽도록 {@link Worker}에 적는다. 실행 줄을 하나로 두어 붙잡은 일 하나가
 * 뒤의 일을 모두 기다리게 한다.
 *
 * <p>두 worker는 시험이 옮기는 시계 하나를 함께 쓴다({@link ManualClock}). 닫았다 다시 띄우는 데 실제로 걸린 시간은 착수 시각에
 * 들어가지 않고, 그사이 흐른 것으로 치는 시간({@link #DOWNTIME})은 시험이 정한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ForecastRestartIntegrationTest {
    private static final int STOP_COUNT = 10;
    private static final int TURN = 8;
    private static final int CAPACITY = 45;
    private static final List<Integer> PASSED_STOPS = List.of(1, 2, 3, 4, 5, 6);
    private static final int PROCESSED_BEFORE_CLOSE = 3;
    private static final List<Integer> CLOSED_STOPS = List.of(2, 3, 4, 5);
    /** 통과가 닫히는 정류장 2~5를 목표로 한 예측 수다. 1~4번 정류장을 떠난 배치가 각각 4·3·2·1개를 낸다. */
    private static final int CLOSED_FORECASTS = 10;
    /** 수신 뒤 착수까지 허용하는 90초를 넘긴 시간이다. 줍기가 찾는 신선도 창보다는 짧다. */
    private static final Duration PAST_CONFIRMATION_LIMIT = Duration.ofMinutes(3);
    /** 수집 한 회차마다 시계를 옮기는 간격이다. */
    private static final Duration ROUND = Duration.ofMillis(100);
    /** 요청부터 응답 수신까지의 시간이다. */
    private static final Duration RESPONSE_TIME = Duration.ofMillis(1);
    /**
     * 닫았다 다시 띄우는 동안 흐른 것으로 치는 시간이다. 끊긴 노선의 회차를 모두 더해도 채점의 짝짓기 간격
     * ({@link ScoringStage#MINIMUM_LEAD})에 못 미치게 둔다. 그 간격을 넘기면 닫기 전의 예측이 다시 뜬 뒤에 도달한 통과와 짝이 되어,
     * 끊지 않고 이어서 처리한 노선과 채점 결과가 달라지는 것이 맞다.
     */
    private static final Duration DOWNTIME = ScoringStage.MINIMUM_LEAD.dividedBy(2);
    /** 실행 줄이 닫힌 뒤에도 돌던 일이 더 도는 시간이다. 닫는 쪽이 기다리지 않으면 그사이 연결 풀이 먼저 닫힌다. */
    private static final long RUNNING_AFTER_CLOSE_MILLIS = 500;
    /** 예보와 채점이 끝나기를 기다리는 한도다. 넘기면 시험이 실패한다. 판정에 드는 시각은 여기서 나오지 않는다. */
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    /** 노선의 배치를 그 노선 안의 순번으로 바꾼다. 배치 번호는 노선마다 달라 노선끼리 견줄 때 쓴다. */
    private static final String ORDINALS = """
        WITH ordinal AS (
            SELECT observation_batch_id, row_number() OVER (ORDER BY observation_batch_id) AS position
            FROM forecast_run WHERE route_id = :route
        )
        """;

    private static final ManualClock CLOCK = new ManualClock();

    private static TestDatabase database;

    @BeforeAll
    static void 컨텍스트가_닫힌_뒤에도_남는_DB를_만든다() {
        database = TestDatabase.create();
    }

    @AfterAll
    static void 만든_DB를_지운다() {
        database.close();
    }

    @Test
    void 실행_줄에_일이_쌓인_채로_닫으면_돌던_배치만_끝내고_다시_뜬_뒤_남은_배치를_처리해_끊지_않은_노선과_채점_결과가_같다(
        CapturedOutput output
    ) throws Exception {
        // given
        JdbcClient stored = JdbcClient.create(new DriverManagerDataSource(database.jdbcUrl(), database.username(),
            database.password()));
        ConfigurableApplicationContext first = startWorker();
        try {
            HikariDataSource firstPool = first.getBean(DataSource.class).unwrap(HikariDataSource.class);
            SelectedRuntime selected = first.getBean(SelectedRuntime.class);
            RouteLanes lanes = first.getBean(RouteLanes.class);
            OffsetDateTime validFrom = CLOCK.now().minusDays(1);
            Route continuous = insertRoute(stored, "204000057", validFrom);
            Route interrupted = insertRoute(stored, "234000050", validFrom);
            Route running = insertRoute(stored, "204000070", validFrom);
            Route delayed = insertRoute(stored, "234000886", validFrom);
            RuntimeSnapshot runtime = insertDeployment(stored, List.of(continuous, interrupted, running, delayed),
                validFrom);
            selected.select(runtime);
            first.getBean(ForecastTrigger.class).pickUp();
            for (final int passedStop : PASSED_STOPS) {
                collect(first, continuous, passedStop);
            }
            assertThat(waitUntil(first, () -> closedForecasts(stored, continuous, "<> 'PENDING'") == CLOSED_FORECASTS))
                .as("끊지 않는 노선은 닫기 전에 예보와 채점을 모두 마쳤다").isTrue();
            for (final int passedStop : PASSED_STOPS.subList(0, PROCESSED_BEFORE_CLOSE)) {
                collect(first, interrupted, passedStop);
            }
            assertThat(waitUntil(first, () -> runs(stored, interrupted).size() == PROCESSED_BEFORE_CLOSE)).isTrue();
            awaitIdle(lanes);
            CountDownLatch held = selected.holdNextSweepUntilClosed(lanes);
            final long runningBatch = collect(first, running, 1);
            assertThat(held.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("돌던 일이 실행 줄을 붙잡았다").isTrue();
            List<Long> waitingBatches = new ArrayList<>();
            for (final int passedStop : PASSED_STOPS.subList(PROCESSED_BEFORE_CLOSE, PASSED_STOPS.size())) {
                waitingBatches.add(collect(first, interrupted, passedStop));
            }
            final long delayedBatch = store(first, delayed, 1, CLOCK.now().minus(PAST_CONFIRMATION_LIMIT));
            assertThat(lanes.queued()).as("두 노선의 처리가 실행 줄에서 기다린다").isEqualTo(2);

            // when
            first.close();
            String closingLog = output.getOut();
            List<String> runningRunAtClose = runsOf(stored, List.of(runningBatch));
            List<String> waitingRunsAtClose = runsOf(stored, waitingBatches);
            List<String> delayedRunAtClose = runsOf(stored, List.of(delayedBatch));
            CLOCK.advance(DOWNTIME);
            Restarted restarted = restart(stored, runtime, interrupted, delayedBatch);
            Scored uninterrupted = scored(stored, continuous);
            List<String> waitingRuns = runsOf(stored, waitingBatches);
            List<String> delayedRun = runsOf(stored, List.of(delayedBatch));

            // then
            assertSoftly(softly -> {
                softly.assertThat(runningRunAtClose).as("돌던 배치는 닫기 전에 예보를 마쳤다")
                    .containsExactly("PUBLISHED:true");
                softly.assertThat(waitingRunsAtClose).as("기다리던 배치는 닫을 때 처리하지 않는다").isEmpty();
                softly.assertThat(delayedRunAtClose).isEmpty();
                softly.assertThat(firstPool.isClosed()).isTrue();
                softly.assertThat(closingLog).contains("event=forecast_stopped discarded=2")
                    .contains("event=scoring_stopped")
                    .doesNotContain("event=forecast_failed")
                    .doesNotContain("event=forecast_sweep_failed")
                    .doesNotContain("event=forecast_task_failed")
                    .doesNotContain("event=scoring_failed")
                    .doesNotContain("event=scoring_replay_failed")
                    .doesNotContain("stop_timeout")
                    .doesNotContain("has been closed");
                softly.assertThat(restarted.settled()).as("다시 뜬 뒤 마감 안에 남은 배치의 예보와 채점이 끝났다").isTrue();
                softly.assertThat(waitingRuns).as("수신 뒤 90초 안에 다시 뜨면 남은 배치를 예보한다")
                    .containsExactly("PUBLISHED:true", "PUBLISHED:true", "PUBLISHED:true");
                softly.assertThat(delayedRun).as("수신 뒤 90초를 넘긴 배치는 늦음으로 닫는다")
                    .containsExactly("LATE:false");
                softly.assertThat(uninterrupted.runs()).hasSize(PASSED_STOPS.size()).containsOnly("PUBLISHED:true");
                softly.assertThat(uninterrupted.passes()).containsExactlyElementsOf(CLOSED_STOPS);
                softly.assertThat(uninterrupted.unresolved().size() + uninterrupted.labels().size())
                    .as("끊지 않은 노선에 저장된 채점 줄이 있다").isPositive();
                softly.assertThat(restarted.scored()).as("끊겼다 이어진 노선의 예보와 채점 결과가 끊지 않은 노선과 같다")
                    .isEqualTo(uninterrupted);
            });
        } finally {
            first.close();
        }
    }

    /**
     * 같은 DB로 worker를 다시 띄우고 기동 직후처럼 줍기를 한 번 돌린 뒤, 끊겼던 노선의 남은 배치와 늦은 배치가 처리되고 채점이
     * 끝나기를 기다린다.
     */
    private static Restarted restart(
        JdbcClient stored,
        RuntimeSnapshot runtime,
        Route interrupted,
        final long delayedBatch
    ) throws Exception {
        ConfigurableApplicationContext second = startWorker();
        try {
            second.getBean(SelectedRuntime.class).select(runtime);
            second.getBean(ForecastTrigger.class).pickUp();
            final boolean settled = waitUntil(second, () -> runs(stored, interrupted).size() == PASSED_STOPS.size()
                && !runsOf(stored, List.of(delayedBatch)).isEmpty()
                && closedForecasts(stored, interrupted, "<> 'PENDING'") == CLOSED_FORECASTS);
            return new Restarted(settled, scored(stored, interrupted));
        } finally {
            second.close();
        }
    }

    /** {@link Worker}에 적힌 설정으로 컨텍스트를 새로 띄운다. 컨텍스트 캐시에 넣지 않으므로 닫는 것은 부른 쪽이 한다. */
    private static ConfigurableApplicationContext startWorker() throws Exception {
        MergedContextConfiguration configuration = BootstrapUtils.resolveTestContextBootstrapper(Worker.class)
            .buildMergedContextConfiguration();
        SmartContextLoader loader = (SmartContextLoader) configuration.getContextLoader();
        return (ConfigurableApplicationContext) loader.loadContext(configuration);
    }

    /** 실행 줄이 받은 일을 모두 끝내기를 기다린다. 줄이 하나라 표시 일이 돌면 그 앞의 일은 모두 끝난 것이다. */
    private static void awaitIdle(RouteLanes lanes) throws InterruptedException {
        CountDownLatch idle = new CountDownLatch(1);
        assertThat(lanes.submit("idle", idle::countDown)).isTrue();
        assertThat(idle.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 노선과 그 판본·정류장, 차량의 정원을 넣는다.
     *
     * @param validFrom 판본이 유효해지고 정원을 알게 된 시각. worker의 시계보다 앞이어야 예보가 그 정원을 쓴다
     */
    static Route insertRoute(JdbcClient stored, String sourceRoute, OffsetDateTime validFrom) {
        String vehicle = "restart-" + sourceRoute;
        final long routeId = stored.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                end_stop_name)
            VALUES (:source, 'GBIS', :source, :name, '출발', '도착') RETURNING id
            """).param("source", sourceRoute).param("name", ModelRoute.of(sourceRoute)).query(Long.class).single();
        final long routeVersionId = stored.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, :turn) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom).param("turn", TURN)
            .query(Long.class).single();
        for (int order = 1; order <= STOP_COUNT; order++) {
            stored.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (:version, :order, :stop, :name, :direction, true)
                """).param("version", routeVersionId).param("order", order).param("stop", "s" + order)
                .param("name", "정류장" + order).param("direction", direction(order)).update();
        }
        stored.sql("""
            INSERT INTO vehicle_capacity (source_id, vehicle_id, capacity, evidence_at)
            VALUES ('GBIS', :vehicle, :capacity, :at)
            """).param("vehicle", vehicle).param("capacity", CAPACITY).param("at", validFrom).update();
        return new Route(routeId, routeVersionId, sourceRoute, vehicle);
    }

    private static String direction(final int order) {
        if (order > TURN) {
            return "DOWN";
        }
        return "UP";
    }

    static RuntimeSnapshot insertDeployment(JdbcClient stored, List<Route> routes, OffsetDateTime validFrom) {
        List<String> modelRoutes = routes.stream().map(route -> ModelRoute.of(route.sourceRoute())).toList();
        final long deploymentId = stored.sql("""
            INSERT INTO model_deployment (deployment_key, release_id, model_key, model_version, bundle_digest,
                prediction_target_version, calculation_version, supported_scope_digest, data_until, state)
            VALUES (?, 'forecast-restart-test', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'CALCULATION_V1',
                ?, ?, 'ACTIVE')
            RETURNING id
            """).params(UUID.randomUUID(), "1".repeat(64), "0".repeat(64), validFrom).query(Long.class).single();
        return new RuntimeSnapshot(
            new ActiveModelDeployment(deploymentId, "CALCULATION_V1", "forecast-restart-test", "1".repeat(64)),
            new SupportedForecastScope(modelRoutes),
            ConfirmedProfileModelFixture.model(modelRoutes, STOP_COUNT, TURN), validFrom.toInstant());
    }

    /**
     * 노선의 차량이 {@code passedStop}을 막 떠난 관측 한 줄을 지금 수집한 것으로 저장한다. worker의 시계를 한 회차 옮긴 시각에
     * 요청하고, 응답을 받은 시각까지 시계를 옮긴 뒤에 저장한다. 그래서 예보의 착수 시각은 수신 시각보다 이르지 않다.
     */
    static long collect(ConfigurableApplicationContext context, Route route, final int passedStop) {
        ManualClock clock = context.getBean(ManualClock.class);
        clock.advance(ROUND);
        OffsetDateTime at = clock.now();
        clock.advance(RESPONSE_TIME);
        return store(context, route, passedStop, at);
    }

    /**
     * 노선의 차량이 {@code passedStop}을 막 떠난 관측 한 줄을 {@code at}에 요청해 {@link #RESPONSE_TIME} 뒤에 받은 것으로 실제 수집
     * 원장에 저장하고 커밋한다. 시계는 옮기지 않는다.
     */
    private static long store(
        ConfigurableApplicationContext context,
        Route route,
        final int passedStop,
        OffsetDateTime at
    ) {
        ObservationBatchLedger ledger = context.getBean(ObservationBatchLedger.class);
        final long batchId = ledger
            .reserve(new CollectionPlan(route.routeVersionId(), at, UUID.randomUUID().toString()), at).batchId();
        ledger.markDispatching(batchId, at, at, "a");
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation("경기70아0001", route.vehicle(), 0,
            route.sourceRoute(), 11, "s" + passedStop, passedStop, 2, 32 - passedStop * 2, 1, 1);
        ledger.conclude(batchId,
            received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            at.plus(RESPONSE_TIME));
        return batchId;
    }

    /** 조건이 참이 되기를 기다린다. 기다리는 동안 채점이 모아 둔 결과를 쓰게 한다. */
    private static boolean waitUntil(ConfigurableApplicationContext context, BooleanSupplier condition)
        throws InterruptedException {
        ScoringCycle scoring = context.getBean(ScoringCycle.class);
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            scoring.flush();
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        scoring.flush();
        return condition.getAsBoolean();
    }

    /** 노선의 예보 실행을 배치 순서로 {@code 결과:예측이 있는지}로 적는다. */
    private static List<String> runs(JdbcClient stored, Route route) {
        return stored.sql("""
            SELECT outcome || ':' || (prediction_count > 0)::text FROM forecast_run
            WHERE route_id = :route ORDER BY observation_batch_id
            """).param("route", route.routeId()).query(String.class).list();
    }

    /** 배치들의 예보 실행을 배치 순서로 {@code 결과:예측이 있는지}로 적는다. 수신 뒤 90초를 넘겨 착수한 실행은 예측이 없다. */
    static List<String> runsOf(JdbcClient stored, List<Long> batches) {
        return stored.sql("""
            SELECT outcome || ':' || (prediction_count > 0)::text FROM forecast_run
            WHERE observation_batch_id IN (:batches) ORDER BY observation_batch_id
            """).param("batches", batches).query(String.class).list();
    }

    private static long closedForecasts(JdbcClient stored, Route route, String stateCondition) {
        return stored.sql("""
            SELECT count(*) FROM forecast_outcome
            WHERE route_id = :route AND target_stop_order IN (:targets) AND state
            """ + stateCondition).param("route", route.routeId()).param("targets", CLOSED_STOPS).query(Long.class)
            .single();
    }

    /**
     * 노선에 저장된 예보와 채점 결과를 노선끼리 견줄 수 있는 모양으로 모은다. 배치와 관측의 번호는 노선마다 다르므로 배치를 그
     * 노선 안의 순번으로 바꿔 적는다.
     */
    private static Scored scored(JdbcClient stored, Route route) {
        List<Integer> passes = stored.sql("""
            SELECT stop_order FROM stop_pass WHERE route_id = :route ORDER BY stop_order
            """).param("route", route.routeId()).query(Integer.class).list();
        List<String> outcomes = stored.sql(ORDINALS + """
            SELECT ordinal.position || ':' || outcome.target_stop_order || ':' || outcome.state || ':'
                || coalesce(outcome.reason, '')
            FROM forecast_outcome outcome
            JOIN ordinal ON ordinal.observation_batch_id = outcome.observation_batch_id
            WHERE outcome.route_id = :route ORDER BY ordinal.position, outcome.target_stop_order
            """).param("route", route.routeId()).query(String.class).list();
        List<String> unresolved = stored.sql(ORDINALS + """
            SELECT ordinal.position || ':' || unresolved.target_stop_order || ':' || unresolved.state || ':'
                || unresolved.reason
            FROM forecast_unresolved unresolved
            JOIN forecast_record record ON record.source_date = unresolved.source_date
                AND record.vehicle_observation_id = unresolved.vehicle_observation_id
            JOIN ordinal ON ordinal.observation_batch_id = record.observation_batch_id
            WHERE unresolved.route_id = :route ORDER BY ordinal.position, unresolved.target_stop_order
            """).param("route", route.routeId()).query(String.class).list();
        List<String> labels = stored.sql("""
            SELECT stop_order || ':' || capacity || ':' || state || ':' || coalesce(reason, '') FROM pass_label
            WHERE route_id = :route ORDER BY stop_order, capacity
            """).param("route", route.routeId()).query(String.class).list();
        return new Scored(runs(stored, route), passes, outcomes, unresolved, labels);
    }

    /**
     * 다시 띄운 worker가 내려가기 전의 결과다.
     *
     * @param settled 마감 안에 남은 배치의 예보와 채점이 끝났는지
     * @param scored 끊겼던 노선에 저장된 예보와 채점 결과
     */
    private record Restarted(boolean settled, Scored scored) {
    }

    /** 시험이 넣은 노선 하나다. */
    record Route(long routeId, long routeVersionId, String sourceRoute, String vehicle) {
    }

    /**
     * 노선 하나에 저장된 예보와 채점 결과다.
     *
     * @param runs 배치 순서의 예보 실행
     * @param passes 닫힌 통과의 정류장
     * @param outcomes 예측마다의 채점 상태와 사유
     * @param unresolved 저장된 미해결 줄
     * @param labels 저장된 정답 줄
     */
    private record Scored(
        List<String> runs,
        List<Integer> passes,
        List<String> outcomes,
        List<String> unresolved,
        List<String> labels
    ) {
    }

    /** 띄울 worker의 설정이다. 시험 클래스가 아니라 설정을 적어 두는 자리여서 시험 메서드가 없다. */
    @SpringBootTest(classes = WorkerApplication.class, properties = {
        "forecast.enabled=true",
        "forecast.interval=1h",
        "forecast.lanes=1"
    })
    @Import({ ExternalDatabase.class, CollectionRuntimeConfiguration.class })
    private static final class Worker {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalDatabase {
        @Bean
        JdbcConnectionDetails externalConnectionDetails() {
            return new JdbcConnectionDetails() {
                @Override
                public String getUsername() {
                    return database.username();
                }

                @Override
                public String getPassword() {
                    return database.password();
                }

                @Override
                public String getJdbcUrl() {
                    return database.jdbcUrl();
                }
            };
        }

        @Bean
        @Primary
        SelectedRuntime selectedRuntime() {
            return new SelectedRuntime();
        }

        /** 닫은 worker와 다시 띄운 worker가 같은 시계를 이어 쓴다. */
        @Bean
        @Primary
        ManualClock manualClock() {
            return CLOCK;
        }
    }

    /** 시험이 고른 모델을 돌려주는 모델 선택이다. 시험이 시키면 모델을 묻는 실행 줄 스레드를 한 번 붙잡는다. */
    static final class SelectedRuntime implements ForecastRuntime {
        private volatile RuntimeSnapshot selected;
        private volatile Hold hold;

        void select(RuntimeSnapshot snapshot) {
            selected = snapshot;
        }

        /**
         * 다음에 실행 줄 스레드가 모델을 물으면 실행 줄이 닫힐 때까지 그 스레드를 붙잡는다. 훑기는 배치를 읽기 전에 모델을 묻으므로,
         * 붙잡힌 훑기는 실행 줄이 닫힌 뒤에야 배치를 읽고 저장한다.
         *
         * @return 스레드를 붙잡으면 열리는 걸쇠
         */
        CountDownLatch holdNextSweepUntilClosed(RouteLanes lanes) {
            Hold next = new Hold(lanes, new CountDownLatch(1));
            hold = next;
            return next.held();
        }

        @Override
        public Optional<RuntimeSnapshot> resolveActive() {
            Hold current = hold;
            if (current != null && Thread.currentThread().getName().startsWith(RouteLanes.THREAD_PREFIX)) {
                hold = null;
                current.untilClosed();
            }
            return Optional.ofNullable(selected);
        }
    }

    private record Hold(RouteLanes lanes, CountDownLatch held) {
        /**
         * 실행 줄이 닫히고 기다리던 일을 모두 버릴 때까지 스레드를 붙잡은 뒤 잠시 더 돈다. 기다리던 일이 버려지기 전에 풀어 주면 그 일이
         * 이어서 돌 수 있다. 시험은 끝날 때 컨텍스트를 반드시 닫으므로 한도 없이 기다린다.
         */
        private void untilClosed() {
            held.countDown();
            while (lanes.isRunning() || lanes.queued() > 0) {
                pause(10);
            }
            pause(RUNNING_AFTER_CLOSE_MILLIS);
        }

        private static void pause(final long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
