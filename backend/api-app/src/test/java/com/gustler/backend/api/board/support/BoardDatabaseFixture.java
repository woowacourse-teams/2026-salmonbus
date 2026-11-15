package com.gustler.backend.api.board.support;

import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class BoardDatabaseFixture {
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";
    /** 지나온 정류소 순번이 stop_order와 같아지는 상태다. 이 픽스처는 차량 위치만 검증하고 상태는 보지 않는다. */
    private static final int RUNNING_STATE_DEPARTED = 2;
    /** 잔여석은 아는 값이거나 모르는 사유 둘 중 하나만 있어야 한다. 이 픽스처는 좌석을 안 본다. */
    private static final String NOT_REPORTED = "NOT_REPORTED";

    private final JdbcClient jdbcClient;

    public BoardDatabaseFixture(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public RouteContext insertRoute(
        String sourceRouteId,
        String displayName,
        String startStopName,
        String endStopName,
        Integer turnSequence,
        String upFirstDepartureTime,
        String upLastDepartureTime,
        String downFirstDepartureTime,
        String downLastDepartureTime,
        OffsetDateTime validFrom
    ) {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (:sourceRouteId, 'GBIS', :sourceRouteId,
                          :displayName, :startStopName, :endStopName)
                RETURNING id
                """)
            .param("sourceRouteId", sourceRouteId)
            .param("sourceRouteId", sourceRouteId)
            .param("displayName", displayName)
            .param("startStopName", startStopName)
            .param("endStopName", endStopName)
            .query(Long.class)
            .single();
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (
                    route_id, turn_sequence,
                    up_first_departure_time, up_last_departure_time,
                    down_first_departure_time, down_last_departure_time,
                    content_digest, valid_from
                ) VALUES (
                    :routeId, :turnSequence,
                    :upFirstDepartureTime, :upLastDepartureTime,
                    :downFirstDepartureTime, :downLastDepartureTime,
                    :contentDigest, :validFrom
                )
                RETURNING id
                """)
            .param("routeId", routeId)
            .param("turnSequence", turnSequence)
            .param("upFirstDepartureTime", upFirstDepartureTime)
            .param("upLastDepartureTime", upLastDepartureTime)
            .param("downFirstDepartureTime", downFirstDepartureTime)
            .param("downLastDepartureTime", downLastDepartureTime)
            .param("contentDigest", "a".repeat(64))
            .param("validFrom", validFrom)
            .query(Long.class)
            .single();

        jdbcClient.sql("UPDATE route SET data_revision=1 WHERE id=:routeId")
            .param("routeId", routeId)
            .update();

        return new RouteContext(routeId, routeVersionId, sourceRouteId);
    }

    public RouteContext insertNextVersion(
        RouteContext route,
        OffsetDateTime validFrom
    ) {
        jdbcClient.sql("UPDATE route_version SET valid_to = :validFrom WHERE id = :routeVersionId")
            .param("validFrom", validFrom)
            .param("routeVersionId", route.routeVersionId())
            .update();
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (
                    route_id, turn_sequence,
                    up_first_departure_time, up_last_departure_time,
                    down_first_departure_time, down_last_departure_time,
                    content_digest, valid_from
                )
                SELECT route_id, turn_sequence,
                       up_first_departure_time, up_last_departure_time,
                       down_first_departure_time, down_last_departure_time,
                       :contentDigest, :validFrom
                FROM route_version
                WHERE id = :previousRouteVersionId
                RETURNING id
                """)
            .param("contentDigest", "d".repeat(64))
            .param("validFrom", validFrom)
            .param("previousRouteVersionId", route.routeVersionId())
            .query(Long.class)
            .single();

        return new RouteContext(route.routeId(), routeVersionId, route.sourceRouteId());
    }

    public void updateHeadway(
        RouteContext route,
        String day,
        Integer minMinutes,
        Integer maxMinutes
    ) {
        jdbcClient.sql("""
                UPDATE route_version
                SET %1$s_min_headway_minutes = :minMinutes, %1$s_max_headway_minutes = :maxMinutes
                WHERE id = :routeVersionId
                """.formatted(day))
            .param("minMinutes", minMinutes)
            .param("maxMinutes", maxMinutes)
            .param("routeVersionId", route.routeVersionId())
            .update();
    }

    public void insertStop(
        RouteContext route,
        final int sequence,
        String stopId,
        String name,
        String direction,
        final boolean boardingAllowed
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id,
                    name, direction, boarding_allowed
                ) VALUES (
                    :routeVersionId, :sequence, :stopId,
                    :name, :direction, :boardingAllowed
                )
                """)
            .param("routeVersionId", route.routeVersionId())
            .param("sequence", sequence)
            .param("stopId", stopId)
            .param("name", name)
            .param("direction", direction)
            .param("boardingAllowed", boardingAllowed)
            .update();
    }

    public long insertModel(
        String releaseId,
        String state,
        OffsetDateTime trainedThrough
    ) {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version,
                    bundle_digest, prediction_target_version, calculation_version,
                    supported_scope_digest, data_until, state, activated_at, retired_at
                ) VALUES (
                    :deploymentKey, :releaseId, 'seat-forecast', 'test-v1',
                    :bundleDigest, 'target-v1', 'calculation-v1',
                    :scopeDigest, :trainedThrough, :state,
                    :activatedAt, :retiredAt
                )
                RETURNING id
                """)
            .param("deploymentKey", UUID.randomUUID())
            .param("releaseId", releaseId)
            .param("bundleDigest", "b".repeat(64))
            .param("scopeDigest", "c".repeat(64))
            .param("trainedThrough", trainedThrough)
            .param("state", state)
            .param("activatedAt", trainedThrough)
            .param(
                "retiredAt",
                retiredAt(state, trainedThrough)
            )
            .query(Long.class)
            .single();
    }

    public long insertBatch(
        RouteContext route,
        OffsetDateTime responseReceivedAt,
        String outcome,
        Integer storedRows
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at,
                    completed_at, outcome, provider_rows, stored_rows, excluded_rows,
                    normalization_version, collection_strategy_version
                ) VALUES (
                    :routeVersionId, :scheduledAt, 1, :attemptKey,
                    :requestedAt, :responseReceivedAt,
                    :completedAt, :outcome, :providerRows, :storedRows, 0,
                    :normalizationVersion, :collectionStrategyVersion
                )
                RETURNING id
                """)
            .param("routeVersionId", route.routeVersionId())
            .param("scheduledAt", responseReceivedAt.minusSeconds(2))
            .param("attemptKey", "board-test-" + UUID.randomUUID())
            .param("requestedAt", responseReceivedAt.minusSeconds(1))
            .param("responseReceivedAt", responseReceivedAt)
            .param("completedAt", responseReceivedAt)
            .param("outcome", outcome)
            .param("providerRows", storedRows)
            .param("storedRows", storedRows)
            .param("normalizationVersion", NORMALIZATION_VERSION)
            .param("collectionStrategyVersion", COLLECTION_STRATEGY_VERSION)
            .query(Long.class)
            .single();
    }

    /**
     * 관측 묶음의 예보 실행 줄을 예측 0건(EMPTY)으로 넣는다. {@link #insertForecast}로 예측을 더하면 PUBLISHED가 된다.
     */
    public void insertPublication(
        final long batchId,
        final long modelDeploymentId,
        OffsetDateTime confirmedAt
    ) {
        insertRun(batchId, modelDeploymentId, "EMPTY", confirmedAt);
    }

    /**
     * 관측 묶음의 예보 실행 줄을 예측 없이 넣는다. 날짜 파티션은 묶음 예약 시각의 한국 날짜다.
     */
    public void insertRun(
        final long batchId,
        Long modelDeploymentId,
        String outcome,
        OffsetDateTime confirmedAt
    ) {
        jdbcClient.sql("""
                INSERT INTO forecast_run (
                    source_date, observation_batch_id, route_id, route_version_id, received_at,
                    confirmed_at, model_deployment_id, outcome, prediction_count, source_incomplete, counted_rows
                )
                SELECT (batch.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, batch.id, version.route_id,
                       batch.route_version_id, batch.response_received_at,
                       :confirmedAt, :modelDeploymentId, :outcome, 0, false, 0
                FROM observation_batch batch
                JOIN route_version version ON version.id = batch.route_version_id
                WHERE batch.id = :batchId
                """)
            .param("batchId", batchId)
            .param("modelDeploymentId", modelDeploymentId)
            .param("outcome", outcome)
            .param("confirmedAt", confirmedAt)
            .update();
    }

    /**
     * observedAt은 더 이상 저장되지 않는다. vehicle_observation.observed_at이 삭제되어
     * 관측 시각은 묶음의 response_received_at을 기준으로 한다. 이 클래스를 쓰는 테스트는
     * 두 곳에 같은 값을 전달하므로 의미는 유지된다. 파라미터를 제거하려면 호출부도
     * 수정해야 하므로 남겨 두었다.
     */
    public long insertObservation(
        RouteContext route,
        final long batchId,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        OffsetDateTime observedAt
    ) {
        return insertObservation(route, batchId, sourceRowNumber, vehicleId, stopOrder, stopId,
            stopOrder, RUNNING_STATE_DEPARTED, null, NOT_REPORTED);
    }

    public long insertObservation(
        RouteContext route,
        final long batchId,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int passedStopOrder,
        final int runningState,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id,
                    passed_stop_order, running_state, remaining_seats, seat_unknown_reason
                ) VALUES (
                    :batchId, :routeVersionId, :sourceRowNumber,
                    :vehicleId, :stopOrder, :stopId,
                    :passedStopOrder, :runningState, :remainingSeats, :seatUnknownReason
                )
                RETURNING id
                """)
                .param("batchId", batchId)
                .param("routeVersionId", route.routeVersionId())
                .param("sourceRowNumber", sourceRowNumber)
                .param("vehicleId", vehicleId)
                .param("stopOrder", stopOrder)
                .param("stopId", stopId)
                .param("passedStopOrder", passedStopOrder)
                .param("runningState", runningState)
                .param("remainingSeats", remainingSeats)
                .param("seatUnknownReason", seatUnknownReason)
                .query(Long.class)
                .single());
    }

    /**
     * 관측 한 줄의 목표 정류장 예측 하나를 예보 기록에 더하고 실행 줄을 PUBLISHED로 바꾼다.
     * 관측 묶음에 그 모델의 실행 줄이 먼저 있어야 한다. 예보 기록은 차량 번호가 있는 관측에만 있다.
     */
    public void insertForecast(
        final long observationId,
        final int targetStopOrder,
        final long modelDeploymentId,
        final double seatFullChance,
        Double expectedSeats
    ) {
        LocalDate sourceDate = jdbcClient.sql("""
                SELECT run.source_date
                FROM forecast_run run
                JOIN vehicle_observation observation
                    ON observation.observation_batch_id = run.observation_batch_id
                WHERE observation.id = :observationId
                  AND run.model_deployment_id = :modelDeploymentId
                """)
            .param("observationId", observationId)
            .param("modelDeploymentId", modelDeploymentId)
            .query(LocalDate.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO forecast_record (
                    source_date, vehicle_observation_id, observation_batch_id, source_row_number,
                    route_id, route_version_id, vehicle_id, passed_stop_order,
                    trip_start_observation_id, chain_start_observation_id, down, capacity, extended,
                    target_stop_orders, expected_seats, full_probabilities
                )
                SELECT :sourceDate, observation.id, observation.observation_batch_id, observation.source_row_number,
                       version.route_id, observation.route_version_id, observation.vehicle_id,
                       observation.passed_stop_order, observation.id, observation.id, false, 45, false,
                       ARRAY[:targetStopOrder], ARRAY[CAST(:expectedSeats AS double precision)],
                       ARRAY[CAST(:seatFullChance AS double precision)]
                FROM vehicle_observation observation
                JOIN route_version version ON version.id = observation.route_version_id
                WHERE observation.id = :observationId
                ON CONFLICT (source_date, vehicle_observation_id) DO UPDATE SET
                    target_stop_orders = forecast_record.target_stop_orders || EXCLUDED.target_stop_orders,
                    expected_seats = forecast_record.expected_seats || EXCLUDED.expected_seats,
                    full_probabilities = forecast_record.full_probabilities || EXCLUDED.full_probabilities
                """)
            .param("sourceDate", sourceDate)
            .param("observationId", observationId)
            .param("targetStopOrder", targetStopOrder)
            .param("expectedSeats", expectedSeats)
            .param("seatFullChance", seatFullChance)
            .update();
        jdbcClient.sql("""
                UPDATE forecast_run SET outcome = 'PUBLISHED', prediction_count = prediction_count + 1
                FROM vehicle_observation observation
                WHERE observation.id = :observationId
                  AND forecast_run.source_date = :sourceDate
                  AND forecast_run.observation_batch_id = observation.observation_batch_id
                """)
            .param("observationId", observationId)
            .param("sourceDate", sourceDate)
            .update();
    }

    public record RouteContext(
        long routeId,
        long routeVersionId,
        String sourceRouteId
    ) {
    }

    private static OffsetDateTime retiredAt(String state, OffsetDateTime trainedThrough) {
        if ("RETIRED".equals(state)) {
            return trainedThrough.plusSeconds(1);
        }
        return null;
    }
}
