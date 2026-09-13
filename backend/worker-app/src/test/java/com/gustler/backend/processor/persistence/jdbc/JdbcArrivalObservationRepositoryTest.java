package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ArrivalCandidate;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcArrivalObservationRepositoryTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);

    /** 판마다 남는 두 판본. 기본값이 없어서 픽스처가 직접 넣는다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 차가 실린 판의 결말. 결말은 CHECK 이 아홉 값으로 막는다. */
    private static final String SUCCESS_ROWS = "SUCCESS_ROWS";

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";

    /** 운행 상태 실측값. 도착 중(1)이면 그 정류소를 아직 안 지났고, 지나감(2)이면 지났다. */
    private static final int RUNNING_STATE_ARRIVING = 1;
    private static final int RUNNING_STATE_DEPARTED = 2;

    /** 잔여석을 모를 때 왜 모르는지. 아는 값과 둘 중 하나만 들어간다. */
    private static final String SEAT_NOT_REPORTED = "NOT_REPORTED";
    private static final int SEATS_LEFT = 12;

    private static final int HIGHEST_STOP_ORDER = 10;
    private static final int STOP_6 = 6;

    /** 버스가 도착 중인 정류소의 순번. 아직 안 지나서 통과 순번은 이것보다 하나 작다. */
    private static final int ARRIVING_STOP_ORDER = 7;
    private static final int PASSED_STOP_ORDER_WHEN_ARRIVING = ARRIVING_STOP_ORDER - 1;

    /** 예보를 낸 관측의 시각. 이 시각보다 뒤인 관측만 회수 대상이다. */
    private static final OffsetDateTime FORECAST_OBSERVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime FIRST_POLL_AFTER_FORECAST =
        OffsetDateTime.parse("2026-08-19T11:14:19.911+09:00");
    private static final OffsetDateTime SECOND_POLL_AFTER_FORECAST =
        OffsetDateTime.parse("2026-08-19T11:14:34.911+09:00");
    private static final OffsetDateTime THIRD_POLL_AFTER_FORECAST =
        OffsetDateTime.parse("2026-08-19T11:14:49.911+09:00");

    private static final int READ_LIMIT = 10;

    /** 넣어둔 관측 셋보다 작은 한도. */
    private static final int READ_LIMIT_TWO = 2;

    @Autowired
    private JdbcArrivalObservationRepository jdbcArrivalObservationRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;

    /** 시도 키와 상류 행 번호는 겹치면 안 된다. ux_batch_attempt 와 ux_observation_source_row 가 막는다. */
    private int insertedBatchCount;
    private int insertedObservationCount;

    @BeforeEach
    void 노선_판본과_경유_정류소를_먼저_저장한다() {
        insertedBatchCount = 0;
        insertedObservationCount = 0;
        routeVersionId = insertRouteVersion(ROUTE_204000057);
    }

    @Test
    void 예보를_낸_시각_뒤의_같은_차량_관측을_시각_오름차순으로_읽는다() {
        // given
        // 늦은 관측을 먼저 넣는다. 행 번호 순서와 시각 순서가 엇갈려야 무엇으로 정렬하는지 드러난다.
        final long later = insertDepartedObservation(
            insertBatch(SECOND_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        final long earlier = insertDepartedObservation(
            insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(ArrivalCandidate::observationId)
            .containsExactly(earlier, later);
    }

    @Test
    void 물어본_시각과_같은_시각의_관측은_안_담는다() {
        // given
        insertDepartedObservation(insertBatch(FORECAST_OBSERVED_AT), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 물어본_차량의_관측만_읽는다() {
        // given
        final long batchId = insertBatch(FIRST_POLL_AFTER_FORECAST);
        insertDepartedObservation(batchId, VEHICLE_204000206, STOP_6);
        insertDepartedObservation(batchId, VEHICLE_204003542, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(candidate -> candidate.vehicle().vehicleId())
            .containsExactly(VEHICLE_204000206);
    }

    @Test
    void 물어본_노선_판본의_관측만_읽는다() {
        // given
        insertDepartedObservation(insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        final long otherRouteVersionId = insertRouteVersion(ROUTE_204000121);
        insertDepartedObservation(
            otherRouteVersionId,
            insertBatch(otherRouteVersionId, FIRST_POLL_AFTER_FORECAST),
            VEHICLE_204000206,
            STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(candidate -> candidate.vehicle().routeVersionId())
            .containsExactly(routeVersionId);
    }

    @Test
    void 관측_시각을_판에서_읽어_온다() {
        // given
        insertDepartedObservation(insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(ArrivalCandidate::observedAt)
            .containsExactly(FIRST_POLL_AFTER_FORECAST.toInstant());
    }

    @Test
    void 통과_순번을_적재가_남긴_열에서_그대로_읽는다() {
        // given
        // 도착 중(1)이라 통과 순번을 상류 순번보다 하나 작게 넣는다.
        // 조회가 운행 상태에서 다시 계산하면 7이 나오고, 열을 그대로 읽으면 6이 나온다.
        insertArrivingObservation(
            insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, ARRIVING_STOP_ORDER);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(ArrivalCandidate::passedStopOrder)
            .containsExactly(PASSED_STOP_ORDER_WHEN_ARRIVING);
    }

    @Test
    void 잔여석을_모르는_관측도_담고_좌석을_모른다고_답한다() {
        // given
        insertSeatUnknownObservation(insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(actual)
            .singleElement()
            .extracting(ArrivalCandidate::hasKnownSeats)
            .isEqualTo(false);
    }

    @Test
    void 한도를_넘는_관측은_안_읽는다() {
        // given
        final long first = insertDepartedObservation(
            insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        final long second = insertDepartedObservation(
            insertBatch(SECOND_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        insertDepartedObservation(insertBatch(THIRD_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> actual = jdbcArrivalObservationRepository.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT_TWO);

        // then
        assertThat(actual)
            .extracting(ArrivalCandidate::observationId)
            .containsExactly(first, second);
    }

    private long insertRouteVersion(
        String publicRouteId
    ) {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(publicRouteId, SOURCE_ID, publicRouteId, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();

        final long versionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, FORECAST_OBSERVED_AT)
            .query(Long.class)
            .single();

        for (int stopOrder = 1; stopOrder <= HIGHEST_STOP_ORDER; stopOrder++) {
            jdbcClient.sql("""
                    INSERT INTO route_stop (
                        route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(versionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", true)
                .update();
        }
        return versionId;
    }

    private long insertBatch(
        OffsetDateTime responseReceivedAt
    ) {
        return insertBatch(routeVersionId, responseReceivedAt);
    }

    /** 관측 시각의 권위가 여기 있다. 응답을 받은 시각을 판에 남긴다. */
    private long insertBatch(
        final long versionId,
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
                versionId, responseReceivedAt, 1,
                "%s-%d".formatted(ROUTE_204000057, insertedBatchCount++),
                responseReceivedAt, responseReceivedAt, SUCCESS_ROWS,
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertDepartedObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder
    ) {
        return insertDepartedObservation(routeVersionId, batchId, vehicleId, stopOrder);
    }

    /** 지나감(2)이라 통과 순번이 상류 순번과 같다. */
    private long insertDepartedObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder
    ) {
        return insertObservation(
            versionId, batchId, vehicleId, stopOrder, stopOrder, RUNNING_STATE_DEPARTED, SEATS_LEFT, null);
    }

    /** 도착 중(1)이라 그 정류소를 아직 안 지났다. 통과 순번이 상류 순번보다 하나 작다. */
    private long insertArrivingObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder
    ) {
        return insertObservation(
            routeVersionId, batchId, vehicleId, stopOrder, stopOrder - 1,
            RUNNING_STATE_ARRIVING, SEATS_LEFT, null);
    }

    /** 잔여석이 비고 모르는 사유만 남은 관측. 둘 중 정확히 하나만 채우라는 CHECK 이 있다. */
    private long insertSeatUnknownObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder
    ) {
        return insertObservation(
            routeVersionId, batchId, vehicleId, stopOrder, stopOrder,
            RUNNING_STATE_DEPARTED, null, SEAT_NOT_REPORTED);
    }

    /** 관측 시각 열은 SAL-84 가 지웠다. 관측 행에는 시각이 없다. */
    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        final int passedStopOrder,
        final int runningState,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        return jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, versionId, insertedObservationCount++,
                vehicleId, stopOrder, stopIdOf(stopOrder), passedStopOrder,
                runningState, remainingSeats, seatUnknownReason
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
