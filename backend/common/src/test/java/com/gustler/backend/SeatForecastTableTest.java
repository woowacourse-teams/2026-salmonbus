package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 행이 닫히는 규칙을 DB 가 지키는지 본다.
 *
 * <p>V8__forecast_settlement.sql 이 건 검사들이라 SQL 을 직접 넣어서 막히는지 확인한다.
 * 저장소를 거치면 저장소가 못 만드는 조합은 시험할 수가 없다.
 */
@IntegrationTest
@Transactional
class SeatForecastTableTest {

    /** 판마다 남는 두 판본. 기본값이 없어서 픽스처가 직접 넣는다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 지나감(2)으로 넣어서 통과 순번이 상류 순번과 같다. 관측 시각 열은 SAL-84 가 지웠다. */
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int SEATS_LEFT = 12;

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String VEHICLE_204000206 = "204000206";
    private static final int PASSED_STOP_ORDER = 6;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int STOPS_TO_TARGET = TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int DEMAND_STATISTICS_REVISION = 3;
    private static final int SEATS_ON_ARRIVAL = 12;
    private static final int NEGATIVE_SEATS_ON_ARRIVAL = -1;
    private static final String UNKNOWN_SCORING_STATE = "REVIEWING";
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime ARRIVAL_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:20:31.402+09:00");
    private static final OffsetDateTime GENERATED_AT = OffsetDateTime.parse("2026-08-19T11:14:05+09:00");
    private static final OffsetDateTime SCORED_AT = OffsetDateTime.parse("2026-08-19T11:25:00+09:00");

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;
    private long modelDeploymentId;
    private long vehicleObservationId;
    private long arrivalObservationId;

    @BeforeEach
    void 예보가_가리키는_행을_먼저_저장한다() {
        final long routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        insertRouteStop(PASSED_STOP_ORDER);
        insertRouteStop(TARGET_STOP_ORDER);
        modelDeploymentId = insertModelDeployment();

        final long batchId = insertObservationBatch("2026-08-19T11:14", RESPONSE_RECEIVED_AT);
        vehicleObservationId = insertObservation(batchId, PASSED_STOP_ORDER);

        final long arrivalBatchId = insertObservationBatch("2026-08-19T11:20", ARRIVAL_RESPONSE_RECEIVED_AT);
        arrivalObservationId = insertObservation(arrivalBatchId, TARGET_STOP_ORDER);
    }

    @Test
    void 회수_상태는_다섯_값만_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertForecast(UNKNOWN_SCORING_STATE, null, null, SCORED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 회수가_끝난_행에만_회수_시각이_남는다() {
        // when & then
        assertThatThrownBy(() -> insertForecast("PENDING", null, null, SCORED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 만석으로_회수한_행에만_도착_잔여석이_남는다() {
        // when & then
        assertThatThrownBy(() ->
            insertForecast("SEAT_MISSING", arrivalObservationId, SEATS_ON_ARRIVAL, SCORED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 도착_관측을_찾은_행에만_도착_관측_id가_남는다() {
        // when & then
        assertThatThrownBy(() -> insertForecast("SKIPPED", arrivalObservationId, null, SCORED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 도착_잔여석은_0석_이상만_저장된다() {
        // when & then
        assertThatThrownBy(() ->
            insertForecast("SETTLED", arrivalObservationId, NEGATIVE_SEATS_ON_ARRIVAL, SCORED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertForecast(
        String scoringState,
        Long arrivalId,
        Integer seatsOnArrival,
        OffsetDateTime scoredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                    model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats, generated_at,
                    scoring_state, arrival_observation_id, seats_on_arrival, scored_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                vehicleObservationId, TARGET_STOP_ORDER, routeVersionId, STOPS_TO_TARGET,
                modelDeploymentId, DEMAND_STATISTICS_REVISION,
                0.41, 0.38, 12.5, GENERATED_AT,
                scoringState, arrivalId, seatsOnArrival, scoredAt
            )
            .update();
    }

    private long insertRoute() {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_204000057, SOURCE_ID, ROUTE_204000057, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    private long insertRouteVersion(
        final long routeId
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, RESPONSE_RECEIVED_AT)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final int stopOrder
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", true)
            .update();
    }

    private long insertModelDeployment() {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                UUID.fromString("0f8b4c1e-6f2a-4c3d-9e51-2b7a8c4d5e6f"),
                "2026-08-19-1", "seat-full-chance", "1.4.0", CONTENT_DIGEST,
                "SEAT_FULL_CHANCE_V1", "CALCULATION_V1", CONTENT_DIGEST,
                RESPONSE_RECEIVED_AT, "ACTIVE"
            )
            .query(Long.class)
            .single();
    }

    private long insertObservationBatch(
        String attemptKey,
        OffsetDateTime responseReceivedAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, outcome,
                    normalization_version, collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                routeVersionId, responseReceivedAt, 1, ROUTE_204000057 + "-" + attemptKey,
                responseReceivedAt, responseReceivedAt, "SUCCESS_ROWS",
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertObservation(
        final long batchId,
        final int stopOrder
    ) {
        return jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, routeVersionId, 0,
                VEHICLE_204000206, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, SEATS_LEFT
            )
            .query(Long.class)
            .single();
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }
}
