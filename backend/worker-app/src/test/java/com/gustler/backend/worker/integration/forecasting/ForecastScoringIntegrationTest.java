package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.application.CollectionBatchService;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import com.gustler.backend.worker.scheduling.ForecastScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예보를 켠 worker({@code forecast.enabled=true})를 실제로 조립해, 수집 원장이 커밋한 관측이 신호·노선별 실행 줄·예보 서비스·
 * 저장·채점까지 실제 빈으로 흐르는지 본다. 모델 선택({@link ForecastRuntime})만 바꿔 확정 프로필 모델을 준다.
 *
 * <p>주기 줍기는 1시간이라 시험 중에는 돌지 않는다. 시험은 줍기를 기동 직후처럼 한 번 불러 노선 판본의 실행 줄 열쇠를 알게 한 뒤,
 * 그 뒤의 처리는 모두 커밋 뒤 신호로만 일어나게 한다. 채점 결과는 주기 쓰기를 기다리지 않고 기다리는 동안 시험이 쓰게 한다.
 * 진단 수신자({@link ForecastTelemetry})를 하나 등록해 채점이 닫은 예측을 받는지도 본다.
 *
 * <p>worker의 시계는 시험이 옮긴다({@link ManualClock}). 수집할 때마다 한 회차씩 옮기고, 예보와 도달 사이의 간격이나 앞뒤 차량의
 * 간격이 필요하면 그만큼 옮긴다. 착수 시각과 채점의 판정이 실제로 흐른 시간에 기대지 않는다.
 */
@SpringBootTest(classes = WorkerApplication.class, properties = {
    "forecast.enabled=true",
    "forecast.interval=1h"
})
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class, ManualClock.Replacement.class,
    ForecastScoringIntegrationTest.Diagnostics.class })
class ForecastScoringIntegrationTest {

    private static final String SOURCE_ROUTE = "204000057";
    private static final String VEHICLE = "forecast-scoring-vehicle";
    private static final String LEAD_VEHICLE = "forecast-scoring-lead";
    private static final String FOLLOWING_VEHICLE = "forecast-scoring-following";
    private static final List<String> VEHICLES = List.of(VEHICLE, LEAD_VEHICLE, FOLLOWING_VEHICLE);
    private static final int SETTLED_STOP = 3;
    /** 예측 기준 시각부터 도달 관측까지 두는 간격이다. 짝짓기 조건({@link ScoringStage#MINIMUM_LEAD}보다 뒤)을 채운다. */
    private static final Duration LEAD = ScoringStage.MINIMUM_LEAD.plusMillis(500);
    /** 앞 차량의 통과부터 뒤 차량의 통과까지 두는 간격이다. 정답 계산이 받는 최소 배차 간격(0.2분)을 채운다. */
    private static final Duration HEADWAY = Duration.ofMillis(12_500);
    /** 수집 한 회차마다 시계를 옮기는 간격이다. */
    private static final Duration ROUND = Duration.ofMillis(100);
    /** 요청부터 응답 수신까지의 시간이다. */
    private static final Duration RESPONSE_TIME = Duration.ofMillis(1);
    private static final int STOP_COUNT = 10;
    private static final int TURN = 8;
    private static final int CAPACITY = 45;
    private static final List<Integer> CLOSED_STOPS = List.of(
            2,
            3,
            4,
            5
    );
    private static final List<Integer> OPEN_STOPS = List.of(6, 7, 8);
    /** 통과가 닫히는 정류장 2~5를 목표로 한 예측 수다. 1~4번 정류장을 떠난 배치가 각각 4·3·2·1개를 낸다. */
    private static final int CLOSED_FORECASTS = 10;
    /** 예보와 채점이 끝나기를 기다리는 한도다. 넘기면 시험이 실패한다. 판정에 드는 시각은 여기서 나오지 않는다. */
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @Autowired
    private CollectionBatchService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ForecastScheduler trigger;

    @Autowired
    private RunForecastsService service;

    @Autowired
    private ScoringCycle scoring;

    @Autowired
    private ClosedForecasts closedForecasts;

    @Autowired
    private ManualClock clock;

    @MockitoBean
    private ForecastRuntime forecastRuntime;

    private long routeId;
    private long routeVersionId;
    private long deploymentId;

    @BeforeEach
    void 확정_프로필_모델로_예보할_노선을_준비한다() {
        OffsetDateTime validFrom = clock.now().minusDays(1);
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
            VALUES (?, 'forecast-scoring-test', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'CALCULATION_V1',
                ?, ?, 'ACTIVE')
            RETURNING id
            """).params(
                    UUID.randomUUID(),
                    "1".repeat(64),
                    "0".repeat(64),
                    validFrom
            ).query(Long.class).single();
        RuntimeSnapshot runtime = new RuntimeSnapshot(
                new ActiveModelDeployment(
                        deploymentId,
                        "CALCULATION_V1",
                        "forecast-scoring-test",
                        "1".repeat(64)
                ),
                new SupportedForecastScope(List.of(modelRoute)),
                ConfirmedProfileModelFixture.model(modelRoute, STOP_COUNT, TURN),
                validFrom.toInstant()
        );
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
        service.states().remove(ModelRoute.of(SOURCE_ROUTE));
        jdbc.sql("DELETE FROM vehicle_capacity WHERE vehicle_id IN (:vehicles)").param("vehicles", VEHICLES).update();
        jdbc.sql("TRUNCATE forecast_run, forecast_record, stop_pass, pass_label, forecast_unresolved").update();
        jdbc.sql("TRUNCATE route, model_deployment, daily_call_quota CASCADE").update();
    }

    @Test
    void 수집_원장이_커밋한_관측이_신호로_예보와_통과_채점까지_흐른다()
        throws InterruptedException {
        // given
        List<Integer> passedStops = List.of(
                1,
                2,
                3,
                4,
                5,
                6
        );

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
        clock.moveTo(later(basis.plus(LEAD), leadDeparted.plus(HEADWAY)));

        // when
        collect(FOLLOWING_VEHICLE, SETTLED_STOP, 36);
        collect(FOLLOWING_VEHICLE, SETTLED_STOP + 1, 34);
        final boolean labeled = waitUntil(() -> settledForecasts() == 2, Instant.now().plus(DEADLINE));
        final boolean reported = waitUntil(() -> settledReports().size() == 2, Instant.now().plus(DEADLINE));

        // then
        assertSoftly(softly -> {
            softly.assertThat(labeled).as("마감 안에 뒤 차량의 예측이 정답과 짝지어졌다").isTrue();
            softly.assertThat(reported).as("정답과 짝지어진 예측을 진단 수신자에 한 번씩 알렸다").isTrue();
            softly.assertThat(settledReports()).allSatisfy(closed -> {
                softly.assertThat(closed.routeVersionId()).isEqualTo(routeVersionId);
                softly.assertThat(closed.modelDeploymentId()).isEqualTo(deploymentId);
                softly.assertThat(closed.stopsToTarget()).isIn(1, 2);
                softly.assertThat(closed.departureSeats()).isNotNull();
            });
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

    /** 진단 수신자가 받은, 이 노선의 목표 정류장을 겨눠 채점됨으로 닫힌 예측이다. */
    private List<ForecastClosed> settledReports() {
        return closedForecasts.closed.stream()
            .filter(closed -> closed.routeId() == routeId && closed.targetStopOrder() == SETTLED_STOP)
            .filter(closed -> closed.labelSeats() != null)
            .toList();
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

    /**
     * 차량 하나가 {@code passedStop}을 막 떠난 관측 한 줄을 실제 수집 원장으로 저장하고 커밋한다. 시계를 한 회차 옮긴 시각에 요청하고,
     * 응답을 받은 시각까지 시계를 옮긴 뒤에 저장한다. 그래서 예보의 착수 시각은 수신 시각보다 이르지 않다.
     */
    private long collect(String vehicle, final int passedStop, final int seats) {
        clock.advance(ROUND);
        OffsetDateTime at = clock.now();
        final long batchId = ledger.prepare(new CollectionPlan(routeVersionId, at, UUID.randomUUID().toString()), at)
            .batchId();

        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                "경기70아0001",
                vehicle,
                0,
                SOURCE_ROUTE,
                11,
                "s" + passedStop,
                passedStop,
                2,
                seats,
                1,
                1
        );
        clock.advance(RESPONSE_TIME);
        ledger.conclude(batchId,
            received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
            clock.now());
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Diagnostics {

        @Bean
        ClosedForecasts closedForecasts() {
            return new ClosedForecasts();
        }
    }

    /** 채점이 닫았다고 알린 예측을 받은 순서대로 적어 두는 진단 수신자다. */
    static final class ClosedForecasts implements ForecastTelemetry {

        private final List<ForecastClosed> closed = new CopyOnWriteArrayList<>();

        @Override
        public void closed(List<ForecastClosed> reported) {
            closed.addAll(reported);
        }
    }
}
