package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
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
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예보를 켠 worker({@code forecast.enabled=true})를 실제로 조립해, 수집 원장이 커밋한 관측이 신호·노선별 실행 줄·예보 서비스·
 * 저장·채점까지 실제 빈으로 흐르는지 본다. 모델 선택({@link ForecastRuntime})만 바꿔 확정 프로필 모델을 준다.
 *
 * <p>주기 줍기는 1시간이라 시험 중에는 돌지 않는다. 시험은 줍기를 기동 직후처럼 한 번 불러 노선 판본의 실행 줄 열쇠를 알게 한 뒤,
 * 그 뒤의 처리는 모두 커밋 뒤 신호로만 일어나게 한다. 채점 결과는 주기 쓰기를 기다리지 않고 기다리는 동안 시험이 쓰게 한다.
 */
@SpringBootTest(classes = WorkerApplication.class, properties = {
    "forecast.enabled=true",
    "forecast.interval=1h"
})
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class })
class PipelineMainlineIntegrationTest {
    private static final String SOURCE_ROUTE = "204000057";
    private static final String VEHICLE = "pipeline-mainline-vehicle";
    private static final String LEAD_VEHICLE = "pipeline-mainline-lead";
    private static final String FOLLOWING_VEHICLE = "pipeline-mainline-following";
    private static final List<String> VEHICLES = List.of(VEHICLE, LEAD_VEHICLE, FOLLOWING_VEHICLE);
    private static final int SETTLED_STOP = 3;
    /** 짝짓기 조건(도달 관측이 예측 기준 시각보다 {@link ScoringStage#MINIMUM_LEAD} 뒤)에 여유를 더한 대기다. */
    private static final Duration LEAD_WAIT = ScoringStage.MINIMUM_LEAD.plusMillis(500);
    /** 정답 계산이 받는 최소 배차 간격(직전 통과와 0.2분)에 여유를 더한 대기다. */
    private static final Duration HEADWAY_WAIT = Duration.ofMillis(12_500);
    private static final int STOP_COUNT = 10;
    private static final int TURN = 8;
    private static final int CAPACITY = 45;
    private static final List<Integer> CLOSED_STOPS = List.of(2, 3, 4, 5);
    private static final List<Integer> OPEN_STOPS = List.of(6, 7, 8);
    /** 통과가 닫히는 정류장 2~5를 목표로 한 예측 수다. 1~4번 정류장을 떠난 배치가 각각 4·3·2·1개를 낸다. */
    private static final int CLOSED_FORECASTS = 10;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PipelineForecastTrigger trigger;

    @Autowired
    private ForecastPipelineService pipeline;

    @Autowired
    private ShadowScoringCycle scoring;

    @MockitoBean
    private ForecastRuntime forecastRuntime;

    private long routeId;
    private long routeVersionId;
    private long deploymentId;

    @BeforeEach
    void 확정_프로필_모델로_예보할_노선을_준비한다() {
        OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
        String modelRoute = ModelRoute.of(SOURCE_ROUTE);
        routeId = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                end_stop_name)
            VALUES (:source, 'GBIS', :source, :name, '출발', '도착') RETURNING id
            """).param("source", SOURCE_ROUTE).param("name", modelRoute).query(Long.class).single();
        routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, :turn) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom).param("turn", TURN)
            .query(Long.class).single();
        insertStops();
        for (String vehicle : VEHICLES) {
            jdbc.sql("""
                INSERT INTO vehicle_capacity (source_id, vehicle_id, capacity, evidence_at)
                VALUES ('GBIS', :vehicle, :capacity, :at)
                """).param("vehicle", vehicle).param("capacity", CAPACITY).param("at", validFrom).update();
        }
        deploymentId = jdbc.sql("""
            INSERT INTO model_deployment (deployment_key, release_id, model_key, model_version, bundle_digest,
                prediction_target_version, calculation_version, supported_scope_digest, data_until, state)
            VALUES (?, 'pipeline-mainline-test', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'CALCULATION_V1',
                ?, ?, 'ACTIVE')
            RETURNING id
            """).params(UUID.randomUUID(), "1".repeat(64), "0".repeat(64), validFrom).query(Long.class).single();
        RuntimeSnapshot runtime = new RuntimeSnapshot(
            new ActiveModelDeployment(deploymentId, "CALCULATION_V1", "pipeline-mainline-test", "1".repeat(64)),
            new SupportedForecastScope(List.of(modelRoute)),
            ConfirmedProfileModelFixture.model(modelRoute, STOP_COUNT, TURN), validFrom.toInstant());
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        trigger.pickUp();
    }

    private void insertStops() {
        for (int order = 1; order <= STOP_COUNT; order++) {
            jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (:version, :order, :stop, :name, :direction, true)
                """).param("version", routeVersionId).param("order", order).param("stop", "s" + order)
                .param("name", "정류장" + order).param("direction", direction(order)).update();
        }
    }

    private static String direction(final int order) {
        if (order > TURN) {
            return "DOWN";
        }
        return "UP";
    }

    /**
     * 넣은 자료와 예보의 노선 상태를 지운다. 예보와 채점은 노선·배치·관측·배포 번호로 메모리 상태를 들고 있어 번호를 되돌리면
     * 다음 시험이 앞 시험의 상태를 이어받는다. 그래서 번호를 되돌리지 않는다.
     */
    @AfterEach
    void 저장한_자료를_정리한다() {
        pipeline.states().remove(ModelRoute.of(SOURCE_ROUTE));
        jdbc.sql("DELETE FROM vehicle_capacity WHERE vehicle_id IN (:vehicles)").param("vehicles", VEHICLES).update();
        jdbc.sql("TRUNCATE forecast_run, forecast_record, stop_pass, pass_label, forecast_unresolved").update();
        jdbc.sql("TRUNCATE route, model_deployment, daily_call_quota CASCADE").update();
    }

    @Test
    void 수집_원장이_커밋한_관측이_신호로_본선_예보와_통과_채점까지_흐르고_옛_경로의_테이블은_건드리지_않는다()
        throws InterruptedException {
        // given
        List<Integer> passedStops = List.of(1, 2, 3, 4, 5, 6);

        // when
        List<Long> batches = new ArrayList<>();
        for (int index = 0; index < passedStops.size(); index++) {
            batches.add(collect(passedStops.get(index), 30 - index * 2));
        }
        final boolean settled = waitUntil(() -> hasSettled(batches), Instant.now().plus(DEADLINE));

        // then
        assertSoftly(softly -> {
            softly.assertThat(settled).as("마감 안에 예보·통과·채점이 모두 끝났다").isTrue();
            softly.assertThat(count("""
                SELECT count(*) FROM forecast_run
                WHERE observation_batch_id IN (:batches) AND outcome = 'PUBLISHED' AND prediction_count > 0
                  AND confirmed_at >= received_at AND confirmed_at <= received_at + interval '90 seconds'
                """, batches)).isEqualTo(batches.size());
            softly.assertThat(count("""
                SELECT count(DISTINCT observation_batch_id) FROM forecast_record
                WHERE observation_batch_id IN (:batches) AND vehicle_id = :vehicle
                  AND cardinality(target_stop_orders) > 0
                  AND cardinality(expected_seats) = cardinality(target_stop_orders)
                  AND cardinality(full_probabilities) = cardinality(target_stop_orders)
                """, batches)).isEqualTo(batches.size());
            softly.assertThat(passStops()).containsExactlyElementsOf(CLOSED_STOPS);
            softly.assertThat(outcomeCount(CLOSED_STOPS, "<> 'PENDING'")).isEqualTo(CLOSED_FORECASTS);
            softly.assertThat(outcomeCount(CLOSED_STOPS, "= 'PENDING'")).isZero();
            softly.assertThat(outcomeCount(OPEN_STOPS, "= 'PENDING'")).isPositive();
            softly.assertThat(outcomeCount(OPEN_STOPS, "<> 'PENDING'")).isZero();
            softly.assertThat(scoredRows()).isPositive();
            softly.assertThat(count("""
                SELECT count(*) FROM observation_batch WHERE id IN (:batches) AND input_confirmed_at IS NOT NULL
                """, batches)).isZero();
            softly.assertThat(hasSettlementContext()).isTrue();
        });
    }

    @Test
    void 예보_기준_시각_10초_뒤에_목표에_닿고_앞_차량의_직전_통과가_있으면_채점이_짝을_지어_정답을_쓴다()
        throws InterruptedException {
        // given
        List<Long> lead = new ArrayList<>();
        for (int passedStop = 1; passedStop <= SETTLED_STOP + 1; passedStop++) {
            lead.add(collect(LEAD_VEHICLE, passedStop, 30 - passedStop));
        }
        collect(FOLLOWING_VEHICLE, 1, 40);
        final long beforeTarget = collect(FOLLOWING_VEHICLE, SETTLED_STOP - 1, 38);
        Instant leadDeparted = waitForConfirmation(lead.get(SETTLED_STOP - 1));
        Instant basis = waitForConfirmation(beforeTarget);
        Instant reachable = later(basis.plus(LEAD_WAIT), leadDeparted.plus(HEADWAY_WAIT));
        waitUntil(() -> Instant.now().isAfter(reachable), reachable.plusSeconds(1));

        // when
        collect(FOLLOWING_VEHICLE, SETTLED_STOP, 36);
        collect(FOLLOWING_VEHICLE, SETTLED_STOP + 1, 34);
        final boolean labeled = waitUntil(() -> settledForecasts() == 2, Instant.now().plus(DEADLINE));

        // then
        assertSoftly(softly -> {
            softly.assertThat(labeled).as("마감 안에 뒤 차량의 예측이 정답과 짝지어졌다").isTrue();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM pass_label
                WHERE route_id = :route AND stop_order = :stop AND state = 'SETTLED' AND label_value IS NOT NULL
                  AND previous_last_observation_id IS NOT NULL AND parameter_hash IS NOT NULL
                """).param("route", routeId).param("stop", SETTLED_STOP).query(Long.class).single()).isOne();
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM forecast_outcome outcome
                JOIN pass_label label ON label.route_id = outcome.route_id AND label.stop_order = :stop
                WHERE outcome.route_id = :route AND outcome.vehicle_id = :vehicle AND outcome.target_stop_order = :stop
                  AND outcome.state = 'SETTLED' AND outcome.label_value = label.label_value
                """).param("route", routeId).param("vehicle", FOLLOWING_VEHICLE).param("stop", SETTLED_STOP)
                .query(Long.class).single()).isEqualTo(2);
            softly.assertThat(jdbc.sql("""
                SELECT count(*) FROM forecast_unresolved u JOIN forecast_record r
                  ON r.source_date = u.source_date AND r.vehicle_observation_id = u.vehicle_observation_id
                WHERE u.route_id = :route AND r.vehicle_id = :vehicle AND u.target_stop_order = :stop
                """).param("route", routeId).param("vehicle", FOLLOWING_VEHICLE).param("stop", SETTLED_STOP)
                .query(Long.class).single()).isZero();
        });
    }

    /** 뒤 차량이 목표 정류장을 겨눈 예측 가운데 정답과 짝지어진 수다. */
    private long settledForecasts() {
        return jdbc.sql("""
            SELECT count(*) FROM forecast_outcome
            WHERE route_id = :route AND vehicle_id = :vehicle AND target_stop_order = :stop AND state = 'SETTLED'
            """).param("route", routeId).param("vehicle", FOLLOWING_VEHICLE).param("stop", SETTLED_STOP)
            .query(Long.class).single();
    }

    /** 예보가 배치를 처리하고 예보 실행 줄에 적은 착수 시각을 기다려 돌려준다. */
    private Instant waitForConfirmation(final long batchId) throws InterruptedException {
        waitUntil(() -> confirmedAt(batchId).isPresent(), Instant.now().plus(DEADLINE));
        return confirmedAt(batchId).orElseThrow();
    }

    private Optional<Instant> confirmedAt(final long batchId) {
        return jdbc.sql("SELECT confirmed_at FROM forecast_run WHERE observation_batch_id = ?").param(batchId)
            .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    private static Instant later(Instant left, Instant right) {
        if (left.isAfter(right)) {
            return left;
        }
        return right;
    }

    private long collect(final int passedStop, final int seats) {
        return collect(VEHICLE, passedStop, seats);
    }

    /** 차량 하나가 {@code passedStop}을 막 떠난 관측 한 줄을 실제 수집 원장으로 저장하고 커밋한다. */
    private long collect(String vehicle, final int passedStop, final int seats) {
        OffsetDateTime at = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MILLIS);
        final long batchId = ledger.reserve(new CollectionPlan(routeVersionId, at, UUID.randomUUID().toString()), at)
            .batchId();
        ledger.markDispatching(batchId, at, at, "a");
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation("경기70아0001", vehicle, 0,
            SOURCE_ROUTE, 11, "s" + passedStop, passedStop, 2, seats, 1, 1);
        ledger.conclude(batchId,
            received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            at.plusNanos(1_000_000));
        return batchId;
    }

    private boolean hasSettled(List<Long> batches) {
        return count("SELECT count(*) FROM forecast_run WHERE observation_batch_id IN (:batches)", batches)
            == batches.size()
            && passStops().size() == CLOSED_STOPS.size()
            && outcomeCount(CLOSED_STOPS, "= 'PENDING'") == 0
            && outcomeCount(CLOSED_STOPS, "<> 'PENDING'") > 0;
    }

    private long count(String sql, List<Long> batches) {
        return jdbc.sql(sql).param("batches", batches).param("vehicle", VEHICLE).query(Long.class).single();
    }

    private List<Integer> passStops() {
        return jdbc.sql("SELECT stop_order FROM stop_pass WHERE route_id = ? ORDER BY stop_order").param(routeId)
            .query(Integer.class).list();
    }

    private long outcomeCount(List<Integer> targets, String stateCondition) {
        return jdbc.sql("""
            SELECT count(*) FROM forecast_outcome
            WHERE route_id = :route AND target_stop_order IN (:targets) AND state
            """ + stateCondition).param("route", routeId).param("targets", targets).query(Long.class).single();
    }

    private long scoredRows() {
        return jdbc.sql("""
            SELECT (SELECT count(*) FROM pass_label WHERE route_id = :route)
                 + (SELECT count(*) FROM forecast_unresolved WHERE route_id = :route)
            """).param("route", routeId).query(Long.class).single();
    }

    private boolean hasSettlementContext() {
        return jdbc.sql("""
            SELECT jsonb_exists(settlement_context, :version) FROM model_deployment WHERE id = :deployment
            """).param("version", Long.toString(routeVersionId)).param("deployment", deploymentId).query(Boolean.class)
            .single();
    }

    /** 조건이 참이 되기를 기다린다. 기다리는 동안 채점이 모아 둔 결과를 쓰게 한다. */
    private boolean waitUntil(BooleanSupplier condition, Instant deadline) throws InterruptedException {
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
}
