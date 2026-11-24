package com.gustler.backend.api.board.support;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 판정보드를 받아 쓰는 기능(즐겨찾기·정류장 상세·연어행동·알림 문구)의 통합 시험이 함께 쓰는 준비다.
 *
 * <p>왕복 노선 하나를 넣는다. 승차 정류장은 종점 방면의 3번 정류장이고, 차량 한 대가 2번 정류장을 떠나 그 정류장까지
 * 한 정류장 남았다. 예측은 예보 실행 줄({@code forecast_run})과 예보 기록({@code forecast_record})에 넣는다.
 */
public class ForecastBoardFixture {

    public static final String ROUTE_ID = "204000057";
    public static final String ROUTE_NAME = "3330";
    public static final String STOP_ID = "277102323";
    public static final String STOP_NAME = "판교역";
    public static final int STOP_SEQUENCE = 3;
    public static final String VEHICLE_ID = "BUS-1";

    private static final String ORIGIN_STOP_ID = "277102321";
    private static final String TURN_STOP_ID = "277102322";
    private static final int TURN_SEQUENCE = 2;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final BoardDatabaseFixture board;

    public ForecastBoardFixture(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.board = new BoardDatabaseFixture(jdbcClient);
    }

    /**
     * 현재 판본의 노선과 정류장 세 곳, 승차 정류장의 좌표를 넣는다. 좌표는 정류장 상세가 우리 노선 정류장을 찾는 데 쓴다.
     */
    public BoardDatabaseFixture.RouteContext insertRoute(OffsetDateTime validFrom) {
        BoardDatabaseFixture.RouteContext route = board.insertRoute(
                ROUTE_ID,
                ROUTE_NAME,
                "기점",
                STOP_NAME,
                TURN_SEQUENCE,
                "04:50",
                "23:30",
                "05:00",
                "23:30",
                validFrom
        );
        board.insertStop(
                route,
                1,
                ORIGIN_STOP_ID,
                "기점",
                "UP",
                true
        );
        board.insertStop(
                route,
                TURN_SEQUENCE,
                TURN_STOP_ID,
                "회차점",
                "UP",
                false
        );
        board.insertStop(
                route,
                STOP_SEQUENCE,
                STOP_ID,
                STOP_NAME,
                "DOWN",
                true
        );
        jdbcClient.sql("""
                INSERT INTO route_stop_location_history (route_version_id, stop_order, stop_id, observed_at, x, y)
                VALUES (:routeVersionId, :stopOrder, :stopId, :observedAt, 127.11, 37.39)
                """)
            .param("routeVersionId", route.routeVersionId())
            .param("stopOrder", STOP_SEQUENCE)
            .param("stopId", STOP_ID)
            .param("observedAt", validFrom)
            .update();
        return route;
    }

    public long insertActiveModel(OffsetDateTime activatedAt) {
        return board.insertModel("model-active", "ACTIVE", activatedAt);
    }

    /**
     * 2번 정류장을 떠난 차량 한 대의 정상 관측 묶음을 넣는다. 예보 실행 줄은 넣지 않는다.
     */
    public ObservedVehicle insertApproachingVehicle(
        BoardDatabaseFixture.RouteContext route,
        OffsetDateTime observedAt
    ) {
        final long batchId = board.insertBatch(
                route,
                observedAt,
                "SUCCESS_ROWS",
                1
        );
        final long observationId = board.insertObservation(
                route,
                batchId,
                1,
                VEHICLE_ID,
                TURN_SEQUENCE,
                TURN_STOP_ID,
                observedAt
        );
        return new ObservedVehicle(batchId, observationId, observedAt);
    }

    /**
     * 차량의 승차 정류장 예측을 예보 기록에 넣고 그 묶음의 실행 줄을 PUBLISHED로 둔다.
     */
    public void insertForecast(
        ObservedVehicle vehicle,
        final long modelId,
        final double seatFullChance,
        final double expectedSeats
    ) {
        board.insertPublication(vehicle.batchId(), modelId, vehicle.observedAt().plusSeconds(2));
        board.insertForecast(
                vehicle.observationId(),
                STOP_SEQUENCE,
                modelId,
                seatFullChance,
                expectedSeats
        );
    }

    /**
     * 예측 없이 끝난 실행 줄을 넣는다.
     *
     * @param outcome PUBLISHED가 아닌 실행 결과
     */
    public void insertRunWithoutForecast(
        ObservedVehicle vehicle,
        final long modelId,
        String outcome
    ) {
        board.insertRun(
                vehicle.batchId(),
                modelId,
                outcome,
                vehicle.observedAt().plusSeconds(2)
        );
    }

    /**
     * 승차 정류장의 도착정보 수신본을 넣는다. 차량은 수신 시각에서 {@code remainingSeconds}초 뒤에 도착하고, 수신본은
     * 수신 시각부터 60초 동안 쓸 수 있다.
     */
    public void insertArrival(Instant receivedAt, final int remainingSeconds) {
        ArrivalSnapshot.Prediction prediction = new ArrivalSnapshot.Prediction(
                VEHICLE_ID,
                1,
                remainingSeconds,
                null,
                1,
                10,
                null,
                null,
                null,
                null
        );
        ArrivalSnapshot.RouteArrival arrival = new ArrivalSnapshot.RouteArrival(
                ROUTE_ID,
                ROUTE_NAME,
                STOP_SEQUENCE,
                null,
                null,
                null,
                null,
                List.of(prediction)
        );
        ArrivalSnapshot snapshot = new ArrivalSnapshot(
                UUID.randomUUID(),
                STOP_ID,
                receivedAt,
                receivedAt.plusSeconds(40),
                receivedAt.plusSeconds(60),
                null,
                List.of(arrival)
        );
        jdbcClient.sql("""
                INSERT INTO arrival_snapshot (
                    id, provider, operation, station_id, received_at, refresh_after_at,
                    stale_at, normalization_version, payload_digest, payload
                ) VALUES (
                    :id, :provider, :operation, :stationId, :receivedAt, :refreshAfterAt,
                    :staleAt, :normalizationVersion, :payloadDigest, CAST(:payload AS jsonb)
                )
                """)
            .param("id", snapshot.id())
            .param("provider", ArrivalSnapshot.PROVIDER)
            .param("operation", ArrivalSnapshot.OPERATION)
            .param("stationId", snapshot.stationId())
            .param("receivedAt", Timestamp.from(snapshot.receivedAt()))
            .param("refreshAfterAt", Timestamp.from(snapshot.refreshAfterAt()))
            .param("staleAt", Timestamp.from(snapshot.staleAt()))
            .param("normalizationVersion", ArrivalSnapshot.NORMALIZATION_VERSION)
            .param("payloadDigest", "a".repeat(64))
            .param("payload", objectMapper.writeValueAsString(snapshot))
            .update();
    }

    /**
     * 롤백하지 않는 시험이 이 준비로 넣은 줄을 비운다. 예보 실행 줄과 예보 기록은 FK가 없어 따로 적는다.
     */
    public void clear() {
        jdbcClient.sql("TRUNCATE route, model_deployment, forecast_run, forecast_record, arrival_snapshot CASCADE")
            .update();
    }

    public record ObservedVehicle(long batchId, long observationId, OffsetDateTime observedAt) {

    }
}
