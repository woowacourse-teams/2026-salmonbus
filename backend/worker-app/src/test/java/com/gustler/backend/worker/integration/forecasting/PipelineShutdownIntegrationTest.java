package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.zaxxer.hikari.HikariDataSource;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.SmartContextLoader;

/**
 * 새 예보 경로를 본선으로 켠 worker를 실제로 띄운 뒤 닫아, 실행 줄에 쌓여 있던 배치와 채점이 모아 둔 결과가 DB 연결 풀이 닫히기
 * 전에 모두 저장되는지 본다.
 *
 * <p>닫힌 뒤의 DB를 봐야 하므로 DB와 컨텍스트를 시험이 직접 만든다. 컨텍스트가 만든 시험 DB는 컨텍스트가 닫힐 때 지워지고, 캐시된
 * 컨텍스트를 닫으면 같은 설정을 쓰는 다른 시험이 쓰지 못한다. 설정은 다른 통합 시험과 같은 방식으로 읽도록 {@link Worker}에 적는다.
 * 채점 결과의 주기 쓰기를 1시간으로 두어, 저장된 채점 결과가 종료 때의 마지막 쓰기에서만 나오게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PipelineShutdownIntegrationTest {
    private static final String SOURCE_ROUTE = "204000057";
    private static final String VEHICLE = "pipeline-shutdown-vehicle";
    private static final int STOP_COUNT = 10;
    private static final int TURN = 8;
    private static final int CAPACITY = 45;
    private static final List<Integer> PASSED_STOPS = List.of(1, 2, 3, 4, 5, 6);
    private static final List<Integer> CLOSED_STOPS = List.of(2, 3, 4, 5);
    /** 통과가 닫히는 정류장 2~5를 목표로 한 예측 수다. 1~4번 정류장을 떠난 배치가 각각 4·3·2·1개를 낸다. */
    private static final int CLOSED_FORECASTS = 10;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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
    void 배치가_실행_줄에_쌓인_채로_컨텍스트를_닫아도_모두_예보하고_채점_결과를_쓴_뒤에_연결_풀을_닫는다(
        CapturedOutput output
    ) throws Exception {
        // given
        ConfigurableApplicationContext context = startWorker();
        try {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            final long routeId = insertRoute(jdbc);
            final long routeVersionId = insertRouteVersion(jdbc, routeId);
            List<Long> batches = new ArrayList<>();
            context.getBean(SelectedRuntime.class).select(insertDeployment(jdbc));
            context.getBean(PipelineForecastTrigger.class).pickUp();
            RouteLanes lanes = context.getBean(RouteLanes.class);
            lanes.submit(SOURCE_ROUTE, untilClosing(context));
            for (int index = 0; index < PASSED_STOPS.size(); index++) {
                batches.add(collect(context, routeVersionId, PASSED_STOPS.get(index), 30 - index * 2));
            }
            assertThat(lanes.queued()).as("닫기 전에는 넣은 배치가 모두 실행 줄에서 기다린다").isEqualTo(batches.size());

            // when
            context.close();

            // then
            JdbcClient stored = JdbcClient.create(new DriverManagerDataSource(database.jdbcUrl(),
                database.username(), database.password()));
            assertSoftly(softly -> {
                softly.assertThat(stored.sql("""
                    SELECT count(*) FROM forecast_run
                    WHERE observation_batch_id IN (:batches) AND outcome = 'PUBLISHED' AND prediction_count > 0
                    """).param("batches", batches).query(Long.class).single()).isEqualTo(batches.size());
                softly.assertThat(stored.sql("SELECT stop_order FROM stop_pass WHERE route_id = ? ORDER BY stop_order")
                    .param(routeId).query(Integer.class).list()).containsExactlyElementsOf(CLOSED_STOPS);
                softly.assertThat(outcomeCount(stored, routeId, "<> 'PENDING'")).isEqualTo(CLOSED_FORECASTS);
                softly.assertThat(outcomeCount(stored, routeId, "= 'PENDING'")).isZero();
                softly.assertThat(stored.sql("""
                    SELECT (SELECT count(*) FROM pass_label WHERE route_id = :route)
                         + (SELECT count(*) FROM forecast_unresolved WHERE route_id = :route)
                    """).param("route", routeId).query(Long.class).single()).isPositive();
                softly.assertThat(pool.isClosed()).isTrue();
                softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_drained")
                    .contains("forecastQueued=0 forecastRunning=0 scoringQueued=0 scoringRunning=0 unwritten=0")
                    .doesNotContain("event=pipeline_shutdown_abandoned");
            });
        } finally {
            context.close();
        }
    }

    /** {@link Worker}에 적힌 설정으로 컨텍스트를 새로 띄운다. 컨텍스트 캐시에 넣지 않으므로 닫는 것은 부른 쪽이 한다. */
    private static ConfigurableApplicationContext startWorker() throws Exception {
        MergedContextConfiguration configuration = BootstrapUtils.resolveTestContextBootstrapper(Worker.class)
            .buildMergedContextConfiguration();
        SmartContextLoader loader = (SmartContextLoader) configuration.getContextLoader();
        return (ConfigurableApplicationContext) loader.loadContext(configuration);
    }

    /** 컨텍스트가 닫히기 시작할 때까지 실행 줄을 붙잡는 일이다. 뒤에 넣은 배치는 닫기가 시작된 뒤에야 처리된다. */
    private static Runnable untilClosing(ConfigurableApplicationContext context) {
        CountDownLatch closing = new CountDownLatch(1);
        context.addApplicationListener(new ApplicationListener<ContextClosedEvent>() {
            @Override
            public void onApplicationEvent(ContextClosedEvent event) {
                closing.countDown();
            }
        });
        return () -> await(closing);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long insertRoute(JdbcClient jdbc) {
        return jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                end_stop_name)
            VALUES (:source, 'GBIS', :source, :name, '출발', '도착') RETURNING id
            """).param("source", SOURCE_ROUTE).param("name", ModelRoute.of(SOURCE_ROUTE)).query(Long.class).single();
    }

    private static long insertRouteVersion(JdbcClient jdbc, final long routeId) {
        OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
        final long routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, :turn) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom).param("turn", TURN)
            .query(Long.class).single();
        for (int order = 1; order <= STOP_COUNT; order++) {
            jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (:version, :order, :stop, :name, :direction, true)
                """).param("version", routeVersionId).param("order", order).param("stop", "s" + order)
                .param("name", "정류장" + order).param("direction", direction(order)).update();
        }
        jdbc.sql("""
            INSERT INTO vehicle_capacity (source_id, vehicle_id, capacity, evidence_at)
            VALUES ('GBIS', :vehicle, :capacity, :at)
            """).param("vehicle", VEHICLE).param("capacity", CAPACITY).param("at", validFrom).update();
        return routeVersionId;
    }

    private static String direction(final int order) {
        if (order > TURN) {
            return "DOWN";
        }
        return "UP";
    }

    private static RuntimeSnapshot insertDeployment(JdbcClient jdbc) {
        OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
        String modelRoute = ModelRoute.of(SOURCE_ROUTE);
        final long deploymentId = jdbc.sql("""
            INSERT INTO model_deployment (deployment_key, release_id, model_key, model_version, bundle_digest,
                prediction_target_version, calculation_version, supported_scope_digest, data_until, state)
            VALUES (?, 'pipeline-shutdown-test', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'CALCULATION_V1',
                ?, ?, 'ACTIVE')
            RETURNING id
            """).params(UUID.randomUUID(), "1".repeat(64), "0".repeat(64), validFrom).query(Long.class).single();
        return new RuntimeSnapshot(
            new ActiveModelDeployment(deploymentId, "CALCULATION_V1", "pipeline-shutdown-test", "1".repeat(64)),
            new SupportedForecastScope(List.of(modelRoute)),
            ConfirmedProfileModelFixture.model(modelRoute, STOP_COUNT, TURN), validFrom.toInstant());
    }

    /** 차량이 {@code passedStop}을 막 떠난 관측 한 줄을 실제 수집 원장으로 저장하고 커밋한다. */
    private static long collect(
        ConfigurableApplicationContext context,
        final long routeVersionId,
        final int passedStop,
        final int seats
    ) {
        ObservationBatchLedger ledger = context.getBean(ObservationBatchLedger.class);
        OffsetDateTime at = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MILLIS);
        final long batchId = ledger.reserve(new CollectionPlan(routeVersionId, at, UUID.randomUUID().toString()), at)
            .batchId();
        ledger.markDispatching(batchId, at, at, "a");
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation("경기70아0001", VEHICLE, 0,
            SOURCE_ROUTE, 11, "s" + passedStop, passedStop, 2, seats, 1, 1);
        ledger.conclude(batchId,
            received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            at.plusNanos(1_000_000));
        return batchId;
    }

    private static long outcomeCount(JdbcClient stored, final long routeId, String stateCondition) {
        return stored.sql("""
            SELECT count(*) FROM forecast_outcome
            WHERE route_id = :route AND target_stop_order IN (:targets) AND state
            """ + stateCondition).param("route", routeId).param("targets", CLOSED_STOPS).query(Long.class).single();
    }

    /** 띄울 worker의 설정이다. 시험 클래스가 아니라 설정을 적어 두는 자리여서 시험 메서드가 없다. */
    @SpringBootTest(classes = WorkerApplication.class, properties = {
        "forecast.enabled=true",
        "forecast.pipeline=v2",
        "forecast.scoring=v2",
        "forecast.interval=1h",
        "forecast.scoring-flush-interval=1h",
        "forecast.shutdown-timeout=30s"
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
    }

    /** 시험이 고른 모델을 돌려주는 모델 선택이다. */
    static final class SelectedRuntime implements ForecastRuntime {
        private volatile RuntimeSnapshot selected;

        void select(RuntimeSnapshot snapshot) {
            selected = snapshot;
        }

        @Override
        public Optional<RuntimeSnapshot> resolveActive() {
            return Optional.ofNullable(selected);
        }
    }
}
