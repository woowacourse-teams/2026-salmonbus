package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.OffsetDateTime;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class JdbcArrivalObservationQueryTest {
    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);

    /** 수집 배치마다 기록할 두 판본. 기본값이 없어 픽스처에서 직접 지정한다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 차량 관측이 포함된 수집 배치의 결과 상태. CHECK 이 아홉 값으로 제한한다. */
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
    private JdbcArrivalObservationQuery jdbcArrivalObservationQuery;

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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
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
        List<ArrivalCandidate> actual = jdbcArrivalObservationQuery.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT_TWO);

        // then
        assertThat(actual)
            .extracting(ArrivalCandidate::observationId)
            .containsExactly(first, second);
    }

    @Test
    void ID_조회도_기존_조회의_시간과_차량과_노선_범위와_한도를_지킨다() {
        // given
        insertDepartedObservation(insertBatch(FORECAST_OBSERVED_AT), VEHICLE_204000206, STOP_6);
        final long later = insertDepartedObservation(
            insertBatch(SECOND_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        final long earlier = insertDepartedObservation(
            insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        insertDepartedObservation(insertBatch(THIRD_POLL_AFTER_FORECAST), VEHICLE_204003542, STOP_6);
        final long otherVersion = insertRouteVersion(ROUTE_204000121);
        insertDepartedObservation(otherVersion, insertBatch(otherVersion, FIRST_POLL_AFTER_FORECAST),
            VEHICLE_204000206, STOP_6);

        // when
        List<Long> ids = jdbcArrivalObservationQuery.findIdsAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT_TWO);
        List<Long> limited = jdbcArrivalObservationQuery.findIdsAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), 1);

        // then
        assertThat(ids).containsExactly(earlier, later);
        assertThat(limited).containsExactly(earlier);
        assertThat(ids).containsExactlyElementsOf(jdbcArrivalObservationQuery.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT_TWO)
                .stream()
                .map(ArrivalCandidate::observationId)
                .toList());
    }

    @Test
    void 같은_시각의_관측은_ID가_작은_순서로_읽는다() {
        // given
        final long firstBatch = insertBatch(FIRST_POLL_AFTER_FORECAST);
        final long secondBatch = insertBatch(SECOND_POLL_AFTER_FORECAST);
        jdbcClient.sql("UPDATE observation_batch SET response_received_at = ? WHERE id = ?")
            .params(FIRST_POLL_AFTER_FORECAST, secondBatch).update();
        final long first = insertDepartedObservation(firstBatch, VEHICLE_204000206, STOP_6);
        final long second = insertDepartedObservation(secondBatch, VEHICLE_204000206, STOP_6);

        // when
        List<Long> ids = jdbcArrivalObservationQuery.findIdsAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT);

        // then
        assertThat(ids).containsExactly(first, second);
    }

    @Test
    void 상세_조회는_요청한_노선과_차량의_관측만_반환한다() {
        // given
        final long batch = insertBatch(FIRST_POLL_AFTER_FORECAST);
        final long wanted = insertDepartedObservation(batch, VEHICLE_204000206, STOP_6);
        final long otherVehicle = insertDepartedObservation(batch, VEHICLE_204003542, STOP_6);
        final long otherVersion = insertRouteVersion(ROUTE_204000121);
        final long otherRoute = insertDepartedObservation(otherVersion,
            insertBatch(otherVersion, FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);

        // when
        List<ArrivalCandidate> details = jdbcArrivalObservationQuery.findByIds(
            routeVersionId, VEHICLE_204000206, List.of(wanted, otherVehicle, otherRoute));

        // then
        assertThat(details).containsExactlyElementsOf(jdbcArrivalObservationQuery.findAfter(
            routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT));
        assertThat(details).extracting(ArrivalCandidate::observationId).containsExactly(wanted);
    }

    @Test
    void 상세_조회도_좌석_미상과_품질_제외_정보를_그대로_반환한다() {
        // given
        final long unknown = insertSeatUnknownObservation(
            insertBatch(FIRST_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        final long excluded = insertDepartedObservation(
            insertBatch(SECOND_POLL_AFTER_FORECAST), VEHICLE_204000206, STOP_6);
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED' WHERE route_version_id = ?")
            .param(routeVersionId).update();

        // when
        List<ArrivalCandidate> details = jdbcArrivalObservationQuery.findByIds(
            routeVersionId, VEHICLE_204000206, List.of(unknown, excluded));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(details).containsExactlyInAnyOrderElementsOf(jdbcArrivalObservationQuery.findAfter(
                routeVersionId, VEHICLE_204000206, FORECAST_OBSERVED_AT.toInstant(), READ_LIMIT));
            softly.assertThat(details).allMatch(candidate -> candidate.qualityDirection() == null);
            softly.assertThat(details).anyMatch(candidate -> !candidate.hasKnownSeats());
        }
    }

    @Test
    void 요청한_ID가_없으면_상세_조회는_빈_목록을_반환한다() {
        // given
        List<Long> ids = List.of();

        // when
        List<ArrivalCandidate> result = jdbcArrivalObservationQuery.findByIds(routeVersionId, VEHICLE_204000206, ids);

        // then
        assertThat(result).isEmpty();
    }

    private long insertRouteVersion(
        String sourceRouteId
    ) {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(sourceRouteId, SOURCE_ID, sourceRouteId, "3330", "범계역", "강남역")
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

    /** 관측 시각은 수집 배치에 기록한 응답 수신 시각을 기준으로 한다. */
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

    /** 관측 시각 열은 SAL-84 에서 제거했다. 관측 행에는 시각이 없다. */
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
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
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
                .single());
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }
}
