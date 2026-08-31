package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.FullSeatStreak;
import com.gustler.backend.processor.ObservedSeats;
import com.gustler.backend.processor.ObservedVehicle;
import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.PrecedingVehicle;
import com.gustler.backend.processor.SeatSlope;
import com.gustler.backend.processor.SeatUnknownReason;
import com.gustler.backend.processor.TrajectoryGap;
import com.gustler.backend.processor.VehicleTrajectory;
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
class JdbcVehicleTrajectoryRepositoryTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    private static final String REPORTED_UNKNOWN = "REPORTED_UNKNOWN";

    private static final String SUCCESS_ROWS = "SUCCESS_ROWS";
    private static final String SUCCESS_EMPTY = "SUCCESS_EMPTY";

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204001188 = "204001188";

    private static final OffsetDateTime EARLIER_POLL = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime LATER_POLL = OffsetDateTime.parse("2026-08-19T11:14:19.911+09:00");

    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int NO_SEAT_LEFT = 0;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;
    private static final int HIGHEST_STOP_ORDER = 10;
    private static final int ENOUGH_BATCHES = 10;
    private static final long MISSING_BATCH_ID = 9_999_999L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcVehicleTrajectoryRepository repository;

    private long routeVersionId;

    /** 시도 키는 판마다 달라야 한다. ux_batch_attempt 가 판본 안에서 그것을 막는다. */
    private int insertedBatchCount;

    @BeforeEach
    void 노선_판본과_경유_정류소를_먼저_저장한다() {
        insertedBatchCount = 0;
        routeVersionId = insertRouteVersion(ROUTE_204000057);
    }

    @Test
    void 예보가_안_붙은_판을_오래된_것부터_준다() {
        // given
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(earlier, later);
    }

    @Test
    void 예보가_끝난_판은_대상에서_빠진다() {
        // given
        final long awaiting = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, LATER_POLL);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(awaiting);
    }

    @Test
    void 차가_한_대도_없던_판도_예보_대상으로_준다() {
        // given
        final long empty = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_EMPTY, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(empty);
    }

    @Test
    void 응답을_못_받은_판은_예보_대상이_아니다() {
        // given
        insertBatch(routeVersionId, null, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_노선_판본의_판은_예보_대상에_섞이지_않는다() {
        // given
        insertBatch(insertRouteVersion(ROUTE_204000121), EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 관측_시각은_수집_묶음의_응답_수신_시각으로_채운다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().observation().observedAt()).isEqualTo(EARLIER_POLL.toInstant());
    }

    @Test
    void 한_차량의_궤적_재료_셋이_같이_나온다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 43);
        insertObservation(earlier, VEHICLE_204003542, STOP_6, NO_SEAT_LEFT);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        final long observationId = insertObservation(later, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual).containsExactly(new VehicleTrajectory(
            observationId,
            new ObservedVehicle(
                VEHICLE_204000206, routeVersionId, STOP_6, LATER_POLL.toInstant(), 40, CROWD_LEVEL_3),
            new ObservedSeats.Known(40),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Known(VEHICLE_204003542, NO_SEAT_LEFT, EARLIER_POLL.toInstant()),
            new FullSeatStreak.SeenToEnd(0),
            43));
    }

    @Test
    void 줄곧_만석이던_차량도_최대_잔여석은_1석이다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, NO_SEAT_LEFT);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, NO_SEAT_LEFT);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then 0으로는 나눌 수 없어 셀 통계 집계와 같은 자리에서 1석을 바닥으로 둔다
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(1);
    }

    @Test
    void 잔여석을_한_번도_안_보여_준_차량도_최대_잔여석은_1석이다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservationWithoutSeats(batchId, VEHICLE_204000206, STOP_6, REPORTED_UNKNOWN);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(1);
    }

    @Test
    void 최대_잔여석은_궤적을_잇는_30분_창_밖의_관측에서도_나온다() {
        // given 궤적은 30분까지만 거슬러 보는데 정원은 그 창 밖에서도 온다
        final long longAgoBatchId = insertBatch(
            routeVersionId, LATER_POLL.minusMinutes(40), SUCCESS_ROWS, null);
        insertObservation(longAgoBatchId, VEHICLE_204000206, STOP_5, 44);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, 12);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(44);
    }

    @Test
    void 최대_잔여석은_예보를_내는_시각까지의_관측에서만_나온다() {
        // given 나중에 더 큰 잔여석이 들어와도 예전 예보를 다시 계산하면 같은 값이 나와야 한다
        final long targetBatchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(targetBatchId, VEHICLE_204000206, STOP_5, 12);
        final long laterBatchId = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(laterBatchId, VEHICLE_204000206, STOP_6, 44);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(targetBatchId);

        // then
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(12);
    }

    @Test
    void 잔여석을_모르면_왜_모르는지까지_같이_준다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservationWithoutSeats(batchId, VEHICLE_204000206, STOP_6, REPORTED_UNKNOWN);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().seats())
            .isEqualTo(new ObservedSeats.Unknown(SeatUnknownReason.REPORTED_UNKNOWN));
    }

    @Test
    void 차가_없던_판이_사이에_있으면_기울기가_판_결손으로_나온다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 43);
        insertBatch(routeVersionId, EARLIER_POLL.plusSeconds(5), SUCCESS_EMPTY, null);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual.getFirst().seatSlope())
            .isEqualTo(new SeatSlope.Unknown(TrajectoryGap.OBSERVATION_BATCH_MISSING));
    }

    @Test
    void 다른_노선_판본의_차량은_앞차로_잡히지_않는다() {
        // given
        final long otherVersionId = insertRouteVersion(ROUTE_204000121);
        final long otherBatch = insertBatch(otherVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(otherVersionId, otherBatch, VEHICLE_204001188, STOP_6, NO_SEAT_LEFT);
        final long batchId = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().precedingVehicle())
            .isEqualTo(new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD));
    }

    @Test
    void 차량_아이디가_없는_관측은_궤적에서_뺀다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, null, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 응답을_못_받은_판은_궤적을_못_낸다() {
        // given
        final long batchId = insertBatch(routeVersionId, null, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 같은_시각의_판이_둘이어도_물어본_판의_궤적을_준다() {
        // given
        final long asked = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(asked, VEHICLE_204000206, STOP_6, 40);
        final long sameMoment = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(sameMoment, VEHICLE_204003542, STOP_5, 12);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(asked);

        // then
        assertThat(actual)
            .extracting(trajectory -> trajectory.observation().vehicleId())
            .containsExactly(VEHICLE_204000206);
    }

    @Test
    void 없는_판을_물어보면_빈_목록을_준다() {
        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(MISSING_BATCH_ID);

        // then
        assertThat(actual).isEmpty();
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
            .params(routeId, CONTENT_DIGEST, EARLIER_POLL)
            .query(Long.class)
            .single();

        for (int stopOrder = 1; stopOrder <= HIGHEST_STOP_ORDER; stopOrder++) {
            jdbcClient.sql("""
                    INSERT INTO route_stop (
                        route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(versionId, stopOrder, stopIdOf(stopOrder), "범계역", "UP", true)
                .update();
        }
        return versionId;
    }

    private long insertBatch(
        final long versionId,
        OffsetDateTime responseReceivedAt,
        String outcome,
        OffsetDateTime forecastCompletedAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                    response_received_at, forecast_completed_at, outcome, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                versionId, EARLIER_POLL, 1, "%s-%d".formatted(ROUTE_204000057, insertedBatchCount++),
                EARLIER_POLL, responseReceivedAt, forecastCompletedAt, outcome, NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(routeVersionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    private void insertObservationWithoutSeats(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        String seatUnknownReason
    ) {
        insertObservation(routeVersionId, batchId, vehicleId, stopOrder, null, seatUnknownReason);
    }

    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(versionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    /** 통과 순번은 지나감(2)이라 상류 순번과 같다. 관측 시각 열은 SAL-84 가 지웠다. */
    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        return jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason, crowd_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, versionId, nextRowNumber(batchId),
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, remainingSeats, seatUnknownReason, CROWD_LEVEL_3
            )
            .query(Long.class)
            .single();
    }

    private int nextRowNumber(
        final long batchId
    ) {
        return jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId)
            .query(Integer.class)
            .single();
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500021%d".formatted(stopOrder);
    }
}
