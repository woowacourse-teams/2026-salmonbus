package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.StopDemandCell;
import com.gustler.backend.processor.StopDemandGeneration;
import com.gustler.backend.processor.StopDemandHourlyTotals;
import com.gustler.backend.processor.StopDemandMeasurement;
import com.gustler.backend.processor.StopDemandStatistics;
import com.gustler.backend.processor.TimeSlot;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcStopDemandStatisticsRepositoryTest {

    /** 판마다 남는 두 판본. 기본값이 없어서 픽스처가 직접 넣는다. */
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    /** 관측 판의 결말. CHECK 이 아홉 값으로 막아서 그중 하나를 쓴다. */
    private static final String OUTCOME_SUCCESS_ROWS = "SUCCESS_ROWS";

    /** 지나감(2)으로 넣어서 통과 순번이 상류 순번과 같다. */
    private static final int RUNNING_STATE_DEPARTED = 2;

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204000311 = "204000311";

    /** 판본에 깔아 두는 정류장. 마지막 하나만 승차할 수 없는 경유 정류장이다. */
    private static final int FIRST_STOP_ORDER = 1;
    private static final int PASSING_ONLY_STOP_ORDER = 13;

    /** 라벨이 붙는 대상 정류장과 지평 둘. */
    private static final int TARGET_STOP_ORDER = 9;
    private static final int NEXT_STOP_AHEAD = 1;
    private static final int TWO_STOPS_AHEAD = 2;

    /** 셀을 만들어 둘 정류장 셋. 넣는 순서와 읽히는 순서를 가르려고 순번을 뒤섞어 고른다. */
    private static final int LOWEST_CELL_STOP_ORDER = 3;
    private static final int MIDDLE_CELL_STOP_ORDER = 5;
    private static final int HIGHEST_CELL_STOP_ORDER = 9;

    /**
     * 차량이 대상 정류장에 닿은 시각. 둘 다 UTC 로 02시 대라 한 시각 묶음이 된다.
     *
     * <p>예측 관측이 딸린 판은 이보다 5분 앞이라, 첫 라벨은 예측과 도착이 UTC 시각 묶음까지 갈린다.
     * 시각 묶음을 도착 판에서 잡는지 예측 판에서 잡는지가 그 라벨에서 드러난다.
     */
    private static final OffsetDateTime FIRST_ARRIVED_AT = OffsetDateTime.parse("2026-08-19T11:02:30+09:00");
    private static final OffsetDateTime SECOND_ARRIVED_AT = OffsetDateTime.parse("2026-08-19T11:50:00+09:00");
    private static final int MINUTES_BEFORE_ARRIVAL = 5;
    private static final Instant ARRIVED_HOUR_START = Instant.parse("2026-08-19T02:00:00Z");

    private static final Instant SCORED_AT = Instant.parse("2026-08-19T03:00:00Z");
    private static final Instant DATA_UNTIL = Instant.parse("2026-08-19T04:00:00Z");

    /** 세대의 기준 시각보다 뒤에 받은 관측. 그 세대를 쓸 수 있는 자리다. */
    private static final Instant READ_AS_OF = Instant.parse("2026-08-19T06:00:00Z");
    private static final Instant SCORED_AFTER_DATA_UNTIL = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-08-19T04:00:03Z");
    private static final OffsetDateTime GENERATED_AT = OffsetDateTime.parse("2026-08-19T10:57:31+09:00");

    /** 예측 시점과 도착 시점의 잔여석. 정원이 40 이라 몫이 이진수로 딱 떨어진다. */
    private static final int SEATS_ON_PREDICTION = 40;
    private static final int SEATS_ON_FIRST_ARRIVAL = 10;
    private static final int SEATS_ON_SECOND_ARRIVAL = 20;

    /** 정원 유도를 가르는 값 셋. 이 차량은 40 을 보인 적이 있고 예보를 낼 때는 12 였다. */
    private static final int OBSERVED_MAX_SEATS = 40;
    private static final int SEATS_ON_PREDICTION_BELOW_MAX = 12;

    /**
     * 기준 시각 뒤에 받은 판의 잔여석과 그 판의 응답 시각. 시각은 DATA_UNTIL 보다 한 시간 뒤다.
     *
     * <p>이 잔여석이 정원으로 잡히면 1 - 10/80 = 0.875 가 나와서 0.75 와 갈린다.
     */
    private static final int SEATS_SEEN_AFTER_DATA_UNTIL = 80;
    private static final OffsetDateTime RESPONSE_RECEIVED_AFTER_DATA_UNTIL =
        OffsetDateTime.parse("2026-08-19T14:00:00+09:00");

    /** 잔여석 10 으로 도착한 정원 40 짜리 차량이 채운 비율. 정원을 12 로 잡으면 0.1667 이 나온다. */
    private static final double FILL_RATE_WITH_CAPACITY_40 = 0.75;

    /** 두 라벨을 한 셀로 묶었을 때의 합. 0.75 와 0.5, 30 과 20, 40 과 40 이다. */
    private static final double MERGED_FILL_RATE_TOTAL = 1.25;
    private static final double MERGED_NET_BOARDING_TOTAL = 50.0;
    private static final double MERGED_CAPACITY_TOTAL = 80.0;
    private static final int MERGED_SAMPLE_COUNT = 2;
    private static final int SINGLE_SAMPLE_COUNT = 1;

    private static final String CALCULATION_VERSION = "observed-max-capacity-v1";
    private static final String OTHER_CALCULATION_VERSION = "fixed-47-capacity-v0";
    private static final int FIRST_REVISION = 1;
    private static final int SECOND_REVISION = 2;
    private static final int NO_GENERATION_YET = 0;

    private static final double AVERAGE_FILL_RATE = 0.62;
    private static final double AVERAGE_NET_BOARDING_RATE = -0.04;
    private static final int CELL_SAMPLE_COUNT = 18;
    private static final int CELL_DAY_COUNT = 6;

    private static final double SEAT_FULL_CHANCE_RAW = 0.41;
    private static final double SEAT_FULL_CHANCE = 0.38;
    private static final double EXPECTED_SEATS = 12.5;
    private static final int DEMAND_STATISTICS_REVISION = 3;
    private static final int SOURCE_ROW_NUMBER = 0;

    @Autowired
    private JdbcStopDemandStatisticsRepository jdbcStopDemandStatisticsRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;
    private long modelDeploymentId;

    @BeforeEach
    void 노선_판본과_정류장과_모델을_먼저_저장한다() {
        final long routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        for (int stopOrder = FIRST_STOP_ORDER; stopOrder <= PASSING_ONLY_STOP_ORDER; stopOrder++) {
            insertRouteStop(stopOrder, stopOrder != PASSING_ONLY_STOP_ORDER);
        }
        modelDeploymentId = insertModelDeployment();
    }

    @Test
    void 한_세대의_셀을_정류장_순번_오름차순으로_읽는다() {
        // given
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, HIGHEST_CELL_STOP_ORDER),
            measurementOf(TimeSlot.MORNING, LOWEST_CELL_STOP_ORDER),
            measurementOf(TimeSlot.MORNING, MIDDLE_CELL_STOP_ORDER))));

        // when
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, READ_AS_OF);

        // then
        assertThat(actual.cells())
            .extracting(StopDemandCell::stopOrder)
            .containsExactly(LOWEST_CELL_STOP_ORDER, MIDDLE_CELL_STOP_ORDER, HIGHEST_CELL_STOP_ORDER);
    }

    @Test
    void 세대가_없는_노선_판본은_빈_셀_목록으로_답한다() {
        // when
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, READ_AS_OF);

        // then
        assertThat(actual).isEqualTo(
            new StopDemandStatistics(routeVersionId, TimeSlot.MORNING, NO_GENERATION_YET, List.of()));
    }

    @Test
    void 세대가_없으면_세대_번호가_0이다() {
        // when
        final int actual =
            jdbcStopDemandStatisticsRepository.currentRevision(routeVersionId, CALCULATION_VERSION);

        // then
        assertThat(actual).isEqualTo(NO_GENERATION_YET);
    }

    @Test
    void 계산_규칙_판이_다른_행은_안_읽는다() {
        // given
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, OTHER_CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // when
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, READ_AS_OF);

        // then
        assertThat(actual.cells()).isEmpty();
    }

    @Test
    void 시간대가_다른_행은_안_읽는다() {
        // given
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // when
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.EVENING, CALCULATION_VERSION, READ_AS_OF);

        // then
        assertThat(actual.cells()).isEmpty();
    }

    @Test
    void 셀이_없는_시간대도_그_세대의_번호를_그대로_받는다() {
        // given 아침 셀만 있는 세대. 저녁은 아직 라벨이 안 쌓였다
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // when
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.EVENING, CALCULATION_VERSION, READ_AS_OF);

        // then 세대가 아예 없는 것(0)과 구별돼야 예보 행에 무엇을 보고 냈는지가 남는다
        assertThat(actual.revision()).isEqualTo(FIRST_REVISION);
    }

    @Test
    void 관측_시각_뒤에_난_세대는_안_읽는다() {
        // given 08시에 받은 관측인데 정오까지의 라벨로 낸 세대가 그 뒤에 생겼다
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // when 그 세대의 기준 시각보다 앞선 관측으로 읽는다
        StopDemandStatistics actual = jdbcStopDemandStatisticsRepository.readAsOf(
            routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, DATA_UNTIL.minusSeconds(1));

        // then 그때는 쓸 수 있는 세대가 없었다
        assertThat(actual.revision()).isZero();
    }

    @Test
    void 세대가_쌓여도_관측_시각까지의_것_중_가장_최근을_읽는다() {
        // given 세대 둘. 둘째는 관측보다 뒤의 라벨까지 들어갔다
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));
        jdbcStopDemandStatisticsRepository.append(new StopDemandGeneration(
            routeVersionId, CALCULATION_VERSION, FIRST_REVISION + 1, READ_AS_OF.plusSeconds(3600), COMPUTED_AT,
            List.of(measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // when
        StopDemandStatistics actual = jdbcStopDemandStatisticsRepository.readAsOf(
            routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, READ_AS_OF);

        // then 덮어썼으면 둘째만 남아서 같은 batch 를 다시 처리할 때 값이 달라진다
        assertThat(actual.revision()).isEqualTo(FIRST_REVISION);
    }

    @Test
    void 회수된_라벨을_정류장과_시각으로_묶어_합을_낸다() {
        // given
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AT);
        insertSettledLabel(
            VEHICLE_204000311, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_SECOND_ARRIVAL, SECOND_ARRIVED_AT, SCORED_AT);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement().isEqualTo(new StopDemandHourlyTotals(
            TARGET_STOP_ORDER,
            ARRIVED_HOUR_START,
            MERGED_FILL_RATE_TOTAL,
            MERGED_NET_BOARDING_TOTAL,
            MERGED_CAPACITY_TOTAL,
            MERGED_SAMPLE_COUNT));
    }

    @Test
    void 지평_1_예보만_센다() {
        // given
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AT);
        insertSettledLabel(
            VEHICLE_204000311, TARGET_STOP_ORDER, TWO_STOPS_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_SECOND_ARRIVAL, SECOND_ARRIVED_AT, SCORED_AT);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement()
            .extracting(StopDemandHourlyTotals::sampleCount)
            .isEqualTo(SINGLE_SAMPLE_COUNT);
    }

    @Test
    void 승차할_수_있는_정류장의_라벨만_센다() {
        // given
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AT);
        insertSettledLabel(
            VEHICLE_204000311, PASSING_ONLY_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_SECOND_ARRIVAL, SECOND_ARRIVED_AT, SCORED_AT);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual)
            .extracting(StopDemandHourlyTotals::stopOrder)
            .containsExactly(TARGET_STOP_ORDER);
    }

    @Test
    void 아직_안_닫힌_예보는_안_센다() {
        // given
        insertPendingForecast(VEHICLE_204000206, TARGET_STOP_ORDER, FIRST_ARRIVED_AT);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 기준_시각_뒤에_회수된_라벨은_안_센다() {
        // given
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AFTER_DATA_UNTIL);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 정원은_그_차량이_보여_준_최대_잔여석이다() {
        // given
        insertObservation(
            VEHICLE_204000206,
            TARGET_STOP_ORDER - NEXT_STOP_AHEAD,
            OBSERVED_MAX_SEATS,
            SECOND_ARRIVED_AT);
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION_BELOW_MAX, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AT);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement()
            .extracting(StopDemandHourlyTotals::fillRateTotal)
            .isEqualTo(FILL_RATE_WITH_CAPACITY_40);
    }

    @Test
    void 기준_시각_뒤에_들어온_잔여석은_정원에_안_들어간다() {
        // given
        insertSettledLabel(
            VEHICLE_204000206, TARGET_STOP_ORDER, NEXT_STOP_AHEAD,
            SEATS_ON_PREDICTION, SEATS_ON_FIRST_ARRIVAL, FIRST_ARRIVED_AT, SCORED_AT);
        insertObservation(
            VEHICLE_204000206,
            TARGET_STOP_ORDER,
            SEATS_SEEN_AFTER_DATA_UNTIL,
            RESPONSE_RECEIVED_AFTER_DATA_UNTIL);

        // when
        List<StopDemandHourlyTotals> actual =
            jdbcStopDemandStatisticsRepository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement()
            .extracting(StopDemandHourlyTotals::fillRateTotal)
            .isEqualTo(FILL_RATE_WITH_CAPACITY_40);
    }

    @Test
    void 한_세대를_덮어쓰면_지난_세대의_행이_안_남는다() {
        // given
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, LOWEST_CELL_STOP_ORDER),
            measurementOf(TimeSlot.MORNING, MIDDLE_CELL_STOP_ORDER))));

        // when
        jdbcStopDemandStatisticsRepository.append(generationOf(SECOND_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, LOWEST_CELL_STOP_ORDER))));

        // then
        StopDemandStatistics actual =
            jdbcStopDemandStatisticsRepository.readAsOf(routeVersionId, TimeSlot.MORNING, CALCULATION_VERSION, READ_AS_OF);
        assertThat(actual.cells())
            .extracting(StopDemandCell::stopOrder)
            .containsExactly(LOWEST_CELL_STOP_ORDER);
    }

    @Test
    void 시간대는_소문자로_저장된다() {
        // when
        jdbcStopDemandStatisticsRepository.append(generationOf(FIRST_REVISION, CALCULATION_VERSION, List.of(
            measurementOf(TimeSlot.MORNING, TARGET_STOP_ORDER))));

        // then
        String actual = readStoredTimeSlot(TARGET_STOP_ORDER);
        assertThat(actual).isEqualTo("morning");
    }

    private StopDemandGeneration generationOf(
        final int revision,
        String calculationVersion,
        List<StopDemandMeasurement> measurements
    ) {
        return new StopDemandGeneration(
            routeVersionId, calculationVersion, revision, DATA_UNTIL, COMPUTED_AT, measurements);
    }

    private static StopDemandMeasurement measurementOf(
        TimeSlot timeSlot,
        final int stopOrder
    ) {
        return new StopDemandMeasurement(timeSlot, new StopDemandCell(
            stopOrder, AVERAGE_FILL_RATE, AVERAGE_NET_BOARDING_RATE, CELL_SAMPLE_COUNT, CELL_DAY_COUNT));
    }

    /**
     * 예측 관측과 도착 관측, 그리고 그 둘을 잇는 회수된 예보 한 벌.
     *
     * <p>예측 관측은 대상 정류장에서 지평만큼 뒤에 있고 도착 관측은 대상 정류장에 있다.
     * 둘은 판이 달라서 도착 시각도 다르다.
     */
    private void insertSettledLabel(
        String vehicleId,
        final int targetStopOrder,
        final int stopsToTarget,
        final int seatsOnPrediction,
        final int seatsOnArrival,
        OffsetDateTime arrivedAt,
        Instant scoredAt
    ) {
        final long predictionObservationId = insertObservation(
            vehicleId,
            targetStopOrder - stopsToTarget,
            seatsOnPrediction,
            arrivedAt.minusMinutes(MINUTES_BEFORE_ARRIVAL));
        final long arrivalObservationId = insertObservation(
            vehicleId, targetStopOrder, seatsOnArrival, arrivedAt);
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                    model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats, generated_at,
                    scoring_state, arrival_observation_id, seats_on_arrival, scored_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?, ?)
                """)
            .params(
                predictionObservationId, targetStopOrder, routeVersionId, stopsToTarget,
                modelDeploymentId, DEMAND_STATISTICS_REVISION,
                SEAT_FULL_CHANCE_RAW, SEAT_FULL_CHANCE, EXPECTED_SEATS, GENERATED_AT,
                arrivalObservationId, seatsOnArrival, scoredAt.atOffset(ZoneOffset.UTC)
            )
            .update();
    }

    /** 아직 라벨을 못 채운 예보. 도착 관측과 도착 잔여석과 회수 시각 셋이 다 비어 있어야 V8 검사를 통과한다. */
    private void insertPendingForecast(
        String vehicleId,
        final int targetStopOrder,
        OffsetDateTime observedAt
    ) {
        final long predictionObservationId = insertObservation(
            vehicleId, targetStopOrder - NEXT_STOP_AHEAD, SEATS_ON_PREDICTION, observedAt);
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                    model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats, generated_at, scoring_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """)
            .params(
                predictionObservationId, targetStopOrder, routeVersionId, NEXT_STOP_AHEAD,
                modelDeploymentId, DEMAND_STATISTICS_REVISION,
                SEAT_FULL_CHANCE_RAW, SEAT_FULL_CHANCE, EXPECTED_SEATS, GENERATED_AT
            )
            .update();
    }

    private String readStoredTimeSlot(
        final int stopOrder
    ) {
        return jdbcClient.sql("""
                SELECT time_slot
                FROM stop_demand_statistics
                WHERE route_version_id = ?
                  AND stop_order = ?
                """)
            .params(routeVersionId, stopOrder)
            .query(String.class)
            .single();
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
            .params(routeId, CONTENT_DIGEST, FIRST_ARRIVED_AT)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final int stopOrder,
        final boolean boardingAllowed
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", boardingAllowed)
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
                FIRST_ARRIVED_AT, "ACTIVE"
            )
            .query(Long.class)
            .single();
    }

    /** 판 하나에 관측 한 행. 판마다 응답 시각이 따로라 도착 시각을 관측마다 정할 수 있다. */
    private long insertObservation(
        String vehicleId,
        final int stopOrder,
        final int remainingSeats,
        OffsetDateTime responseReceivedAt
    ) {
        final long observationBatchId = insertObservationBatch(responseReceivedAt);
        return jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                observationBatchId, routeVersionId, SOURCE_ROW_NUMBER,
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, remainingSeats
            )
            .query(Long.class)
            .single();
    }

    private long insertObservationBatch(
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
                routeVersionId, responseReceivedAt, 1, UUID.randomUUID().toString(),
                responseReceivedAt, responseReceivedAt, OUTCOME_SUCCESS_ROWS,
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION
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
