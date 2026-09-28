package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.application.evaluation.ForecastEvaluationWriter;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class DemandSampleRecordingTest {

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
    private static final int PASSED_STOP_ORDER = 8;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int NEXT_TARGET_STOP_ORDER = 10;
    private static final int ARRIVAL_STOP_ORDER = 9;
    private static final int STOPS_TO_TARGET = TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int STOPS_TO_NEXT_TARGET = NEXT_TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int DEMAND_STATISTICS_REVISION = 3;

    /** 판이 상류 응답을 받은 시각. 관측 시각의 권위가 여기 있다. */
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime ARRIVAL_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:20:31.402+09:00");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-19T02:14:05Z");
    private static final Instant NEXT_GENERATED_AT = Instant.parse("2026-08-19T02:14:06Z");
    private static final Instant SCORED_AT = Instant.parse("2026-08-19T02:25:00Z");

    @Autowired
    private ForecastEvaluationWriter evaluationWriter;

    @Autowired
    private RouteDataQualityAccess qualityAccess;

    @Autowired
    private JdbcClient jdbcClient;

    private long routeId;
    private long routeVersionId;
    private long modelDeploymentId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류소와_모델과_관측을_먼저_저장한다() {
        routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        qualityAccess.lockByRoute(routeId);
        insertRouteStop(PASSED_STOP_ORDER);
        insertRouteStop(TARGET_STOP_ORDER);
        insertRouteStop(NEXT_TARGET_STOP_ORDER);
        modelDeploymentId = insertModelDeployment();
        final long observationBatchId = insertObservationBatch("2026-08-19T11:14", RESPONSE_RECEIVED_AT);
        vehicleObservationId = insertObservation(observationBatchId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
    }

    @Test
    void 좌석_결측이나_건너뜀으로_회수한_예보는_돌려주지_않는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        saveForecasts(List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            forecastOf(NEXT_TARGET_STOP_ORDER, STOPS_TO_NEXT_TARGET, NEXT_GENERATED_AT)));

        // when
        List<SettledForecast> actual = evaluationWriter.complete(List.of(
            ForecastEvaluation.completed(
                vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.SeatMissing(arrivalObservationId), SCORED_AT),
            ForecastEvaluation.completed(
                vehicleObservationId, NEXT_TARGET_STOP_ORDER, new ArrivalLabel.Skipped(), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 정산과_함께_통계에_쓸_원본_값과_도착시각을_기록한다() {
        long arrival = insertArrivalObservation();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));

        evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        assertThat(jdbcClient.sql("""
            SELECT route_version_id, prediction_observation_id, arrival_observation_id,
                   vehicle_id, target_stop_order, arrived_at, scored_at,
                   prediction_remaining_seats, arrival_remaining_seats
            FROM stop_demand_pending_sample WHERE prediction_observation_id = ?
            """).param(vehicleObservationId).query((rs, n) -> List.of(
                rs.getLong("route_version_id"), rs.getLong("prediction_observation_id"),
                rs.getLong("arrival_observation_id"), rs.getString("vehicle_id"),
                rs.getInt("target_stop_order"), rs.getObject("arrived_at", OffsetDateTime.class).toInstant(),
                rs.getObject("scored_at", OffsetDateTime.class).toInstant(),
                rs.getInt("prediction_remaining_seats"), rs.getInt("arrival_remaining_seats"))).single())
            .containsExactly(routeVersionId, vehicleObservationId, arrival, VEHICLE_204000206,
                TARGET_STOP_ORDER, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant(), SCORED_AT, SEATS_LEFT, 0);
    }

    @Test
    void 정산_재시도는_처리_대상을_중복_기록하지_않는다() {
        long arrival = insertArrivalObservation();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        var settlement = ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT);

        evaluationWriter.complete(List.of(settlement));
        evaluationWriter.complete(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);

        // 누적 완료 후 처리 대상을 지운 경우에도 정산 상태가 재기록을 막는다.
        jdbcClient.sql("DELETE FROM stop_demand_pending_sample WHERE prediction_observation_id = ?")
            .param(vehicleObservationId).update();
        evaluationWriter.complete(List.of(settlement));
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 처리_대상_저장에_실패하면_정산도_취소된다() {
        long arrival = insertArrivalObservation();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        // 이 테스트 transaction 안에서만 INSERT 실패를 유발한다.
        jdbcClient.sql("""
            ALTER TABLE stop_demand_pending_sample ADD CONSTRAINT test_reject_sample
            CHECK (prediction_remaining_seats < 0)
            """).update();
        jdbcClient.sql("SAVEPOINT before_settlement").update();

        assertThatThrownBy(() -> evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT))))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_settlement").update();
        assertThat(readStoredLabel(TARGET_STOP_ORDER)).isEqualTo(new StoredLabel("PENDING", null, null, null));
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 후속_작업의_롤백은_정산과_처리_대상을_함께_되돌린다() {
        long arrival = insertArrivalObservation();
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        var settlement = ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT);
        jdbcClient.sql("SAVEPOINT before_settlement").update();
        evaluationWriter.complete(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_settlement").update();

        assertThat(readStoredLabel(TARGET_STOP_ORDER)).isEqualTo(new StoredLabel("PENDING", null, null, null));
        assertThat(pendingSampleCount()).isZero();
        evaluationWriter.complete(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"distant", "non_boarding", "missing_seats"})
    void 통계_입력_조건을_벗어난_정산은_처리_대상에_넣지_않는다(String excludedReason) {
        long arrival = insertArrivalObservation();
        int target = excludedReason.equals("distant") ? NEXT_TARGET_STOP_ORDER : TARGET_STOP_ORDER;
        int horizon = excludedReason.equals("distant") ? STOPS_TO_NEXT_TARGET : 1;
        saveForecasts(List.of(forecastOf(target, horizon, GENERATED_AT)));
        if (excludedReason.equals("non_boarding")) {
            jdbcClient.sql("UPDATE route_stop SET boarding_allowed = false WHERE route_version_id = ? AND stop_order = ?")
                .params(routeVersionId, target).update();
        }
        if (excludedReason.equals("missing_seats")) {
            jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats = NULL, seat_unknown_reason = 'NOT_REPORTED' WHERE id = ?")
                .param(vehicleObservationId).update();
        }

        evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, target, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        assertThat(readStoredLabel(target).scoringState()).isEqualTo("SETTLED");
        assertThat(pendingSampleCount()).isZero();
    }

    /** 예보를 낸 뒤 다음 판에서 대상 정류소를 지난 그 차량의 관측. */
    @Test
    void 이전_판정_버전의_예보는_도착_좌석을_저장하되_후보정_증분에는_포함하지_않는다() {
        // given
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE route_data_quality SET quality_revision = quality_revision + 1 WHERE route_id = ?")
            .param(routeId).update();

        // when
        List<SettledForecast> actual = evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbcClient.sql("SELECT seats_on_arrival FROM forecast_evaluation WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(Integer.class).single()).isZero();
        // 6시간 통계는 후보정과 달리 예보의 이전 quality_revision 자체를 제외하지 않는다.
        assertThat(pendingSampleCount()).isEqualTo(1);
    }

    @Test
    void 편도가_제외된_뒤에는_도착_결과와_후보정_증분을_저장하지_않는다() {
        // given
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED' WHERE start_observation_id = ?")
            .param(vehicleObservationId).update();

        // when
        List<SettledForecast> actual = evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 다른_방향의_도착_관측으로_예보를_닫지_않는다() {
        // given
        saveForecasts(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE route_version SET turn_sequence = 9 WHERE id = ?")
            .param(routeVersionId).update();

        // when
        List<SettledForecast> actual = evaluationWriter.complete(List.of(ForecastEvaluation.completed(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM forecast_evaluation WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(pendingSampleCount()).isZero();
    }

    private int pendingSampleCount() {
        return jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE prediction_observation_id = ?")
            .param(vehicleObservationId).query(Integer.class).single();
    }

    private void saveForecasts(List<SeatForecast> forecasts) {
        ForecastEvaluationTestData.saveForecasts(jdbcClient, forecasts);
    }

    private SeatForecast forecastOf(
        final int targetStopOrder,
        final int stopsToTarget,
        Instant generatedAt
    ) {
        return new SeatForecast(
            vehicleObservationId,
            routeVersionId,
            targetStopOrder,
            stopsToTarget,
            modelDeploymentId,
            DEMAND_STATISTICS_REVISION,
            0.41,
            0.38,
            12.5,
            generatedAt);
    }

    private long insertArrivalObservation() {
        final long arrivalBatchId = insertObservationBatch("2026-08-19T11:20", ARRIVAL_RESPONSE_RECEIVED_AT);
        return insertObservation(arrivalBatchId, VEHICLE_204000206, 0, ARRIVAL_STOP_ORDER);
    }

    private StoredLabel readStoredLabel(
        final int targetStopOrder
    ) {
        return jdbcClient.sql("""
                SELECT scoring_state, arrival_observation_id, seats_on_arrival, scored_at
                FROM forecast_evaluation
                WHERE vehicle_observation_id = ?
                  AND target_stop_order = ?
                """)
            .params(vehicleObservationId, targetStopOrder)
            .query((resultSet, rowNumber) -> new StoredLabel(
                resultSet.getString("scoring_state"),
                resultSet.getObject("arrival_observation_id", Long.class),
                resultSet.getObject("seats_on_arrival", Integer.class),
                instantOf(resultSet.getObject("scored_at", OffsetDateTime.class))))
            .single();
    }

    private static Instant instantOf(
        OffsetDateTime timestamp
    ) {
        return timestamp == null ? null : timestamp.toInstant();
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
        String vehicleId,
        final int sourceRowNumber,
        final int stopOrder
    ) {
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, routeVersionId, sourceRowNumber,
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, SEATS_LEFT
            )
            .query(Long.class)
            .single());
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }

    /** 예보 행에 남은 회수 결과 네 열. */
    private record StoredLabel(
        String scoringState,
        Long arrivalObservationId,
        Integer seatsOnArrival,
        Instant scoredAt
    ) {
    }
}
