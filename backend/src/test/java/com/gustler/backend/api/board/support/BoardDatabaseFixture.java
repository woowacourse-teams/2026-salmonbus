package com.gustler.backend.api.board.support;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class BoardDatabaseFixture {

    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    /** 지나온 정류소 순번이 stop_order 와 같아지는 상태다. 이 픽스처는 차량 위치만 쓰고 상태는 안 본다. */
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
                ) VALUES (:publicRouteId, 'GBIS', :sourceRouteId,
                          :displayName, :startStopName, :endStopName)
                RETURNING id
                """)
            .param("publicRouteId", sourceRouteId)
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

        return new RouteContext(routeId, routeVersionId, sourceRouteId);
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
                "RETIRED".equals(state) ? trainedThrough.plusSeconds(1) : null
            )
            .query(Long.class)
            .single();
    }

    public long insertBatch(
        RouteContext route,
        OffsetDateTime responseReceivedAt,
        OffsetDateTime forecastCompletedAt,
        String outcome,
        Integer storedRows
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, forecast_completed_at,
                    completed_at, outcome, provider_rows, stored_rows, excluded_rows,
                    normalization_version
                ) VALUES (
                    :routeVersionId, :scheduledAt, 1, :attemptKey,
                    :requestedAt, :responseReceivedAt, :forecastCompletedAt,
                    :completedAt, :outcome, :providerRows, :storedRows, 0,
                    :normalizationVersion
                )
                RETURNING id
                """)
            .param("routeVersionId", route.routeVersionId())
            .param("scheduledAt", responseReceivedAt.minusSeconds(2))
            .param("attemptKey", "board-test-" + UUID.randomUUID())
            .param("requestedAt", responseReceivedAt.minusSeconds(1))
            .param("responseReceivedAt", responseReceivedAt)
            .param("forecastCompletedAt", forecastCompletedAt)
            .param("completedAt", responseReceivedAt)
            .param("outcome", outcome)
            .param("providerRows", storedRows)
            .param("storedRows", storedRows)
            .param("normalizationVersion", NORMALIZATION_VERSION)
            .query(Long.class)
            .single();
    }

    /**
     * observedAt 은 더 이상 저장되지 않는다. vehicle_observation.observed_at 이 지워졌고
     * 관측 시각은 묶음의 response_received_at 이 권위다. 이 클래스를 쓰는 테스트가 두 곳에
     * 같은 값을 넘기고 있어 뜻은 그대로다. 파라미터를 지우려면 호출부까지 손봐야 해서 남겨뒀다.
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
        return jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id,
                    passed_stop_order, running_state, seat_unknown_reason
                ) VALUES (
                    :batchId, :routeVersionId, :sourceRowNumber,
                    :vehicleId, :stopOrder, :stopId,
                    :stopOrder, :runningState, :seatUnknownReason
                )
                RETURNING id
                """)
            .param("batchId", batchId)
            .param("routeVersionId", route.routeVersionId())
            .param("sourceRowNumber", sourceRowNumber)
            .param("vehicleId", vehicleId)
            .param("stopOrder", stopOrder)
            .param("stopId", stopId)
            .param("runningState", RUNNING_STATE_DEPARTED)
            .param("seatUnknownReason", NOT_REPORTED)
            .query(Long.class)
            .single();
    }

    public void insertForecast(
        RouteContext route,
        final long observationId,
        final int targetStopOrder,
        final int stopsToTarget,
        final long modelDeploymentId,
        final double seatFullChance,
        Double expectedSeats,
        OffsetDateTime generatedAt
    ) {
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id,
                    stops_to_target, model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats,
                    generated_at, scoring_state
                ) VALUES (
                    :observationId, :targetStopOrder, :routeVersionId,
                    :stopsToTarget, :modelDeploymentId, 1,
                    :seatFullChance, :seatFullChance, :expectedSeats,
                    :generatedAt, 'PENDING'
                )
                """)
            .param("observationId", observationId)
            .param("targetStopOrder", targetStopOrder)
            .param("routeVersionId", route.routeVersionId())
            .param("stopsToTarget", stopsToTarget)
            .param("modelDeploymentId", modelDeploymentId)
            .param("seatFullChance", seatFullChance)
            .param("expectedSeats", expectedSeats)
            .param("generatedAt", generatedAt)
            .update();
    }

    public record RouteContext(
        long routeId,
        long routeVersionId,
        String sourceRouteId
    ) {
    }
}
