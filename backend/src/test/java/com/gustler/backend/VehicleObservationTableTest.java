package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class VehicleObservationTableTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final int STOP_ORDER = 6;
    private static final int PASSED_STOP_ORDER = 6;
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final long MISSING_BATCH_ID = 9_999_999L;
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDateTime SEOUL_WALL_CLOCK = LocalDateTime.of(2026, 8, 19, 11, 14, 4, 911_000_000);

    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int SEATS_43 = 43;
    private static final int CROWD_LEVEL_3 = 3;
    private static final String REPORTED_UNKNOWN = "REPORTED_UNKNOWN";
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";
    private static final String ATTEMPT_KEY_IN_KST = "204000057-2026-08-19T11:14-kst";
    private static final String ATTEMPT_KEY_IN_UTC = "204000057-2026-08-19T11:14-utc";

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;
    private long batchId;

    @BeforeEach
    void 노선_판본과_정류소와_수집_묶음을_먼저_저장한다() {
        final long routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        insertRouteStop(routeVersionId);
        batchId = insertObservationBatch(ATTEMPT_KEY, OBSERVED_AT);
    }

    @Test
    void 차량_관측은_수집_묶음이_먼저_있어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservation(MISSING_BATCH_ID, VEHICLE_204000206, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 수집이_재시도되어도_같은_묶음의_같은_버스는_한_번만_저장된다() {
        // given
        insertObservation(batchId, VEHICLE_204000206, 0);

        // when & then
        assertThatThrownBy(() -> insertObservation(batchId, VEHICLE_204000206, 1))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void KST로_넣든_UTC로_넣든_한국_시간으로_조회할_수_있다() {
        // given
        OffsetDateTime inKst = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
        OffsetDateTime inUtc = OffsetDateTime.parse("2026-08-19T02:14:04.911Z");

        // when
        insertObservationBatch(ATTEMPT_KEY_IN_KST, inKst);
        insertObservationBatch(ATTEMPT_KEY_IN_UTC, inUtc);
        List<LocalDateTime> actual = jdbcClient
            .sql("""
                SELECT response_received_at AT TIME ZONE 'Asia/Seoul'
                FROM observation_batch
                WHERE attempt_key IN (?, ?)
                ORDER BY attempt_key
                """)
            .params(ATTEMPT_KEY_IN_KST, ATTEMPT_KEY_IN_UTC)
            .query(LocalDateTime.class)
            .list();

        // then
        assertThat(actual).containsExactly(SEOUL_WALL_CLOCK, SEOUL_WALL_CLOCK);
    }

    @Test
    void 잔여석을_알면_모르는_사유가_비어_있어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservationWithSeats(SEATS_43, REPORTED_UNKNOWN))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 잔여석을_모르면_사유만_두고_저장된다() {
        // when
        insertObservationWithSeats(null, REPORTED_UNKNOWN);

        // then
        assertThat(storedRowCount()).isEqualTo(1);
    }

    @Test
    void 잔여석과_모르는_사유_중_하나는_있어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservationWithSeats(null, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 아는_잔여석은_0석_이상이어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservationWithSeats(-1, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 잔여석을_모르는_사유는_REPORTED_UNKNOWN과_NOT_REPORTED만_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservationWithSeats(null, "SENSOR_BROKEN"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void 혼잡도_1부터_4는_그대로_저장된다(
        final int crowdLevel
    ) {
        // when
        insertObservationWithCrowdLevel(crowdLevel);

        // then
        assertThat(storedRowCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void 혼잡도는_1부터_4까지만_저장된다(
        final int crowdLevel
    ) {
        // when & then
        assertThatThrownBy(() -> insertObservationWithCrowdLevel(crowdLevel))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void 운행_상태_0과_1과_2는_그대로_저장된다(
        final int runningState
    ) {
        // when
        insertObservationWithRunningState(runningState);

        // then
        assertThat(storedRowCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {3, -1})
    void 운행_상태는_0과_1과_2만_저장된다(
        Integer runningState
    ) {
        // when & then
        assertThatThrownBy(() -> insertObservationWithRunningState(runningState))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 통과_순번은_0석_이상이어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservationWithPassedStopOrder(-1))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 첫_정류소에_도착_중인_차량의_통과_순번_0도_저장된다() {
        // when
        insertObservationWithPassedStopOrder(0);

        // then
        assertThat(storedRowCount()).isEqualTo(1);
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
            .params(routeId, CONTENT_DIGEST, OBSERVED_AT)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final long versionId
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(versionId, STOP_ORDER, STOP_205000217, "범계역", "UP", true)
            .update();
    }

    private long insertObservationBatch(
        String attemptKey,
        OffsetDateTime responseReceivedAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, outcome, normalization_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                routeVersionId, OBSERVED_AT, 1, attemptKey,
                OBSERVED_AT, responseReceivedAt, "SUCCESS_ROWS", NORMALIZATION_VERSION
            )
            .query(Long.class)
            .single();
    }

    private void insertObservation(
        final long targetBatchId,
        String vehicleId,
        final int sourceRowNumber
    ) {
        insertObservation(
            targetBatchId, vehicleId, sourceRowNumber,
            RUNNING_STATE_DEPARTED, SEATS_43, null, CROWD_LEVEL_3);
    }

    private void insertObservationWithSeats(
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        insertObservation(
            batchId, VEHICLE_204003542, 0,
            RUNNING_STATE_DEPARTED, remainingSeats, seatUnknownReason, CROWD_LEVEL_3);
    }

    private void insertObservationWithCrowdLevel(
        Integer crowdLevel
    ) {
        insertObservation(
            batchId, VEHICLE_204003542, 0,
            RUNNING_STATE_DEPARTED, SEATS_43, null, crowdLevel);
    }

    private void insertObservationWithRunningState(
        Integer runningState
    ) {
        insertObservation(
            batchId, VEHICLE_204003542, 0,
            runningState, SEATS_43, null, CROWD_LEVEL_3);
    }

    private void insertObservation(
        final long targetBatchId,
        String vehicleId,
        final int sourceRowNumber,
        Integer runningState,
        Integer remainingSeats,
        String seatUnknownReason,
        Integer crowdLevel
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason, crowd_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                targetBatchId, routeVersionId, sourceRowNumber,
                vehicleId, STOP_ORDER, STOP_205000217, PASSED_STOP_ORDER,
                runningState, remainingSeats, seatUnknownReason, crowdLevel
            )
            .update();
    }

    private void insertObservationWithPassedStopOrder(
        final int passedStopOrder
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, crowd_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                batchId, routeVersionId, 0,
                VEHICLE_204003542, STOP_ORDER, STOP_205000217, passedStopOrder,
                RUNNING_STATE_DEPARTED, SEATS_43, CROWD_LEVEL_3
            )
            .update();
    }

    private int storedRowCount() {
        return jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId)
            .query(Integer.class)
            .single();
    }
}
