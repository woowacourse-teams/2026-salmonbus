package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ArrivalLabel;
import com.gustler.backend.processor.ForecastSettlement;
import com.gustler.backend.processor.PendingForecast;
import com.gustler.backend.processor.SeatForecast;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcSeatForecastRepositoryTest {

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
    private static final int NEXT_TARGET_STOP_ORDER = 10;
    private static final int ARRIVAL_STOP_ORDER = 9;
    private static final int STOPS_TO_TARGET = TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int STOPS_TO_NEXT_TARGET = NEXT_TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int DEMAND_STATISTICS_REVISION = 3;

    /** 판이 상류 응답을 받은 시각. 관측 시각의 권위가 여기 있다. */
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");

    /**
     * 관측 행에 적힌 시각. 판이 받은 시각과 일부러 다르게 넣는다.
     *
     * <p>이 열은 없어질 예정이라 회수 대상을 읽을 때 안 본다. 값이 다르면 어느 쪽을 읽었는지 드러난다.
     */

    private static final OffsetDateTime ARRIVAL_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:20:31.402+09:00");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-19T02:14:05Z");
    private static final Instant NEXT_GENERATED_AT = Instant.parse("2026-08-19T02:14:06Z");
    private static final Instant FORECAST_COMPLETED_AT = Instant.parse("2026-08-19T02:14:07Z");
    private static final Instant SCORED_AT = Instant.parse("2026-08-19T02:25:00Z");
    private static final int SEATS_ON_ARRIVAL_WHEN_FULL = 0;
    private static final int READ_LIMIT = 10;

    @Autowired
    private JdbcSeatForecastRepository jdbcSeatForecastRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private long routeId;
    private long routeVersionId;
    private long modelDeploymentId;
    private long observationBatchId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류소와_모델과_관측을_먼저_저장한다() {
        routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        insertRouteStop(PASSED_STOP_ORDER);
        insertRouteStop(TARGET_STOP_ORDER);
        insertRouteStop(NEXT_TARGET_STOP_ORDER);
        modelDeploymentId = insertModelDeployment();
        observationBatchId = insertObservationBatch("2026-08-19T11:14", RESPONSE_RECEIVED_AT);
        vehicleObservationId = insertObservation(observationBatchId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
    }

    @Test
    void 한_판의_예보를_한_번에_쓴다() {
        // given
        List<SeatForecast> forecasts = List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            forecastOf(NEXT_TARGET_STOP_ORDER, STOPS_TO_NEXT_TARGET, NEXT_GENERATED_AT));

        // when
        jdbcSeatForecastRepository.save(forecasts);

        // then
        List<Integer> actual = readTargetStopOrders();
        assertThat(actual).containsExactly(TARGET_STOP_ORDER, NEXT_TARGET_STOP_ORDER);
    }

    @Test
    void 같은_관측과_같은_대상_정류장에는_예보가_하나만_남는다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, NEXT_GENERATED_AT)));

        // then
        List<Integer> actual = readTargetStopOrders();
        assertThat(actual).containsExactly(TARGET_STOP_ORDER);
    }

    @Test
    void 갓_쓴_예보는_아직_안_닫힌_상태다() {
        // when
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // then
        StoredLabel actual = readStoredLabel(TARGET_STOP_ORDER);
        assertThat(actual).isEqualTo(new StoredLabel("PENDING", null, null, null));
    }

    @Test
    void 예보를_다_쓰면_판에_예보_완료_시각이_찍힌다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.markForecastCompleted(observationBatchId, FORECAST_COMPLETED_AT);

        // then
        Instant actual = readForecastCompletedAt(observationBatchId);
        assertThat(actual).isEqualTo(FORECAST_COMPLETED_AT);
    }

    @Test
    void 차량이_한_대도_없던_판에도_예보_완료_시각이_찍힌다() {
        // given
        final long emptyBatchId = insertObservationBatch("2026-08-19T11:16", RESPONSE_RECEIVED_AT.plusMinutes(2));

        // when
        jdbcSeatForecastRepository.markForecastCompleted(emptyBatchId, FORECAST_COMPLETED_AT);

        // then
        Instant actual = readForecastCompletedAt(emptyBatchId);
        assertThat(actual).isEqualTo(FORECAST_COMPLETED_AT);
    }

    @Test
    void 아직_안_닫힌_예보만_회수_대상으로_읽는다() {
        // given
        jdbcSeatForecastRepository.save(List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            forecastOf(NEXT_TARGET_STOP_ORDER, STOPS_TO_NEXT_TARGET, NEXT_GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Skipped(), SCORED_AT)));

        // when
        List<PendingForecast> actual = jdbcSeatForecastRepository.findPending(routeVersionId, READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(PendingForecast::targetStopOrder)
            .containsExactly(NEXT_TARGET_STOP_ORDER);
    }

    @Test
    void 회수_대상은_관측_시각을_판에서_읽어_온다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        List<PendingForecast> actual = jdbcSeatForecastRepository.findPending(routeVersionId, READ_LIMIT);

        // then
        assertThat(actual)
            .extracting(PendingForecast::observedAt)
            .containsExactly(RESPONSE_RECEIVED_AT.toInstant());
    }

    @Test
    void 차량_아이디가_없는_관측의_예보도_회수_대상으로_읽는다() {
        // given
        final long namelessObservationId = insertObservation(observationBatchId, null, 1, PASSED_STOP_ORDER);
        jdbcSeatForecastRepository.save(List.of(new SeatForecast(
            namelessObservationId, routeVersionId, TARGET_STOP_ORDER, STOPS_TO_TARGET,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));

        // when
        List<PendingForecast> actual = jdbcSeatForecastRepository.findPending(routeVersionId, READ_LIMIT);

        // then
        assertThat(actual).singleElement().extracting(PendingForecast::vehicleId).isNull();
    }

    @Test
    void 만석으로_회수하면_도착_관측과_도착_잔여석이_같이_남는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // then
        StoredLabel actual = readStoredLabel(TARGET_STOP_ORDER);
        assertThat(actual).isEqualTo(
            new StoredLabel("SETTLED", arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL, SCORED_AT));
    }

    @Test
    void 좌석_결측으로_회수하면_도착_관측만_남는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.SeatMissing(arrivalObservationId),
            SCORED_AT)));

        // then
        StoredLabel actual = readStoredLabel(TARGET_STOP_ORDER);
        assertThat(actual).isEqualTo(new StoredLabel("SEAT_MISSING", arrivalObservationId, null, SCORED_AT));
    }

    @Test
    void 건너뛴_예보는_도착_관측_없이_닫힌다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Skipped(), SCORED_AT)));

        // then
        StoredLabel actual = readStoredLabel(TARGET_STOP_ORDER);
        assertThat(actual).isEqualTo(new StoredLabel("SKIPPED", null, null, SCORED_AT));
    }

    @Test
    void 만석으로_회수하면_당일_성적_집계가_바로_는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // then
        StoredOutcome actual = readStoredOutcome(STOPS_TO_TARGET);
        assertThat(actual).isEqualTo(
            new StoredOutcome(1, 1, 0.41, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant()));
    }

    @Test
    void 자리가_남은_채_회수하면_건수만_늘고_만석_수는_안_는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrivalObservationId, 7), SCORED_AT)));

        // then
        StoredOutcome actual = readStoredOutcome(STOPS_TO_TARGET);
        assertThat(actual).isEqualTo(
            new StoredOutcome(1, 0, 0.41, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant()));
    }

    @Test
    void 같은_예보_거리의_회수가_쌓이면_한_줄에_더해진다() {
        // given 같은 거리의 예보 둘. 하나는 만석, 하나는 자리 남음
        final long arrivalObservationId = insertArrivalObservation();
        final long secondObservationId = insertObservation(observationBatchId, "204000207", 1, PASSED_STOP_ORDER);
        jdbcSeatForecastRepository.save(List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            new SeatForecast(
                secondObservationId, routeVersionId, TARGET_STOP_ORDER, STOPS_TO_TARGET,
                modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.21, 0.20, 12.5, GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(
            new ForecastSettlement(
                vehicleObservationId, TARGET_STOP_ORDER,
                new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL), SCORED_AT),
            new ForecastSettlement(
                secondObservationId, TARGET_STOP_ORDER,
                new ArrivalLabel.Settled(arrivalObservationId, 7), SCORED_AT)));

        // then
        StoredOutcome actual = readStoredOutcome(STOPS_TO_TARGET);
        assertThat(actual).isEqualTo(
            new StoredOutcome(2, 1, 0.62, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant()));
    }

    @Test
    void 좌석_결측이나_건너뜀으로_회수하면_당일_성적_집계는_안_생긴다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            forecastOf(NEXT_TARGET_STOP_ORDER, STOPS_TO_NEXT_TARGET, NEXT_GENERATED_AT)));

        // when
        jdbcSeatForecastRepository.settle(List.of(
            new ForecastSettlement(
                vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.SeatMissing(arrivalObservationId), SCORED_AT),
            new ForecastSettlement(
                vehicleObservationId, NEXT_TARGET_STOP_ORDER, new ArrivalLabel.Skipped(), SCORED_AT)));

        // then
        assertThat(countStoredOutcomes()).isZero();
    }

    @Test
    void 이미_닫힌_예보를_다시_회수해도_당일_성적_집계는_두_번_안_는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        ForecastSettlement settlement = new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT);
        jdbcSeatForecastRepository.settle(List.of(settlement));

        // when
        jdbcSeatForecastRepository.settle(List.of(settlement));

        // then
        StoredOutcome actual = readStoredOutcome(STOPS_TO_TARGET);
        assertThat(actual.rowCount()).isEqualTo(1);
    }

    @Test
    void 집계가_없으면_원본에서_채워_넣고_그_값을_돌려준다() {
        // given 정산은 됐는데 집계 표가 비어 있다. 이 표가 생기기 전 정산된 행이 그렇다
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));
        jdbcClient.sql("DELETE FROM same_day_full_outcomes").update();

        // when
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plusSeconds(60));

        // then 값도 돌려주고 표도 다시 채워져 있다
        assertThat(actual.get(STOPS_TO_TARGET)).isEqualTo(new SameDayFullOutcomes(1, 1, 0.41));
        assertThat(readStoredOutcome(STOPS_TO_TARGET)).isEqualTo(
            new StoredOutcome(1, 1, 0.41, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant()));
    }

    @Test
    void 예보_시각이_집계에_반영된_도착보다_앞이면_표는_두고_원본에서_그_시각_기준으로_센다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // when 도착보다 앞선 시각의 batch 가 뒤늦게 예보를 받는다
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().minusSeconds(60));

        // then 그 시각엔 도착이 없었고, 표의 값은 그대로다
        assertThat(actual).isEmpty();
        assertThat(readStoredOutcome(STOPS_TO_TARGET).rowCount()).isEqualTo(1);
    }

    @Test
    void 같은_노선의_다른_판본에서_난_예보도_한_성적으로_센다() {
        // given 첫 판본의 예보 하나와, 개편된 판본의 예보 하나가 둘 다 도착까지 확인됐다
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL), SCORED_AT)));

        final long laterVersionId = insertLaterRouteVersion();
        final long laterObservationId = insertObservation(
            insertObservationBatch(laterVersionId, "2026-08-19T11:30", RESPONSE_RECEIVED_AT.plusMinutes(16)),
            laterVersionId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
        final long laterArrivalId = insertObservation(
            insertObservationBatch(laterVersionId, "2026-08-19T11:36", ARRIVAL_RESPONSE_RECEIVED_AT.plusMinutes(16)),
            laterVersionId, VEHICLE_204000206, 0, ARRIVAL_STOP_ORDER);
        jdbcSeatForecastRepository.save(List.of(new SeatForecast(
            laterObservationId, laterVersionId, TARGET_STOP_ORDER, STOPS_TO_TARGET,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.21, 0.20, 12.5, NEXT_GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            laterObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(laterArrivalId, 7), SCORED_AT)));

        // when 어느 판본으로 묻든
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plus(Duration.ofMinutes(20)));

        // then 노선 하나의 성적으로 합쳐진다
        assertThat(actual.get(STOPS_TO_TARGET)).isEqualTo(new SameDayFullOutcomes(2, 1, 0.31));
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

    @Test
    void 오늘_도착이_확인된_예보의_성적을_예보_거리마다_읽는다() {
        // given 도착이 확인된 예보 하나를 만든다
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // when 그 도착보다 뒤 시각으로 묻는다
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plusSeconds(60));

        // then
        assertThat(actual.get(STOPS_TO_TARGET))
            .isEqualTo(new SameDayFullOutcomes(1, 1, 0.41));
    }

    @Test
    void 예보_시각과_같은_순간에_도착한_것도_성적에_센다() {
        // given 그 순간에 이미 확정된 과거 사건이라 미래를 보고 답하는 것이 아니다
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // when 도착 시각과 같은 시각으로 묻는다
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant());

        // then
        assertThat(actual).containsKey(STOPS_TO_TARGET);
    }

    @Test
    void 예보_시각보다_뒤에_도착한_것은_성적에_안_센다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // when 그 도착보다 앞선 시각으로 묻는다
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().minusSeconds(60));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 아직_도착이_확인_안_된_예보는_성적에_안_센다() {
        // given 회수를 안 한 예보다
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plusSeconds(60));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 어제_도착한_예보는_오늘_성적에_안_센다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(insertArrivalObservation(), SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // when 한국 시각으로 다음 날에 묻는다
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plus(Duration.ofDays(1)));

        // then
        assertThat(actual).isEmpty();
    }

    /** 예보를 낸 뒤 다음 판에서 대상 정류소를 지난 그 차량의 관측. */
    private long insertArrivalObservation() {
        final long arrivalBatchId = insertObservationBatch("2026-08-19T11:20", ARRIVAL_RESPONSE_RECEIVED_AT);
        return insertObservation(arrivalBatchId, VEHICLE_204000206, 0, ARRIVAL_STOP_ORDER);
    }

    private List<Integer> readTargetStopOrders() {
        return jdbcClient.sql("""
                SELECT target_stop_order
                FROM seat_forecast
                WHERE vehicle_observation_id = ?
                ORDER BY target_stop_order
                """)
            .param(vehicleObservationId)
            .query(Integer.class)
            .list();
    }

    private StoredLabel readStoredLabel(
        final int targetStopOrder
    ) {
        return jdbcClient.sql("""
                SELECT scoring_state, arrival_observation_id, seats_on_arrival, scored_at
                FROM seat_forecast
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

    private Instant readForecastCompletedAt(
        final long batchId
    ) {
        return jdbcClient.sql("""
                SELECT forecast_completed_at
                FROM observation_batch
                WHERE id = ?
                """)
            .param(batchId)
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("forecast_completed_at", OffsetDateTime.class)))
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

    private long insertLaterRouteVersion() {
        jdbcClient.sql("UPDATE route_version SET valid_to = ? WHERE id = ?")
            .params(RESPONSE_RECEIVED_AT.plusMinutes(15), routeVersionId)
            .update();
        final long laterVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, "1".repeat(64), RESPONSE_RECEIVED_AT.plusMinutes(15))
            .query(Long.class)
            .single();
        for (int stopOrder : List.of(PASSED_STOP_ORDER, TARGET_STOP_ORDER, NEXT_TARGET_STOP_ORDER)) {
            jdbcClient.sql("""
                    INSERT INTO route_stop (
                        route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(laterVersionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", true)
                .update();
        }
        return laterVersionId;
    }

    private long insertObservationBatch(
        String attemptKey,
        OffsetDateTime responseReceivedAt
    ) {
        return insertObservationBatch(routeVersionId, attemptKey, responseReceivedAt);
    }

    private long insertObservationBatch(
        final long versionId,
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
                versionId, responseReceivedAt, 1, ROUTE_204000057 + "-" + attemptKey,
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
        return insertObservation(batchId, routeVersionId, vehicleId, sourceRowNumber, stopOrder);
    }

    private long insertObservation(
        final long batchId,
        final long versionId,
        String vehicleId,
        final int sourceRowNumber,
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
                batchId, versionId, sourceRowNumber,
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
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

    private StoredOutcome readStoredOutcome(
        final int stopsToTarget
    ) {
        return jdbcClient.sql("""
                SELECT row_count, actual_full_count, raw_full_chance_sum, settled_through
                FROM same_day_full_outcomes
                WHERE route_id = ?
                  AND stops_to_target = ?
                """)
            .params(routeId, stopsToTarget)
            .query((resultSet, rowNumber) -> new StoredOutcome(
                resultSet.getInt("row_count"),
                resultSet.getInt("actual_full_count"),
                resultSet.getDouble("raw_full_chance_sum"),
                instantOf(resultSet.getObject("settled_through", OffsetDateTime.class))))
            .single();
    }

    private long countStoredOutcomes() {
        return jdbcClient.sql("SELECT count(*) FROM same_day_full_outcomes WHERE route_id = ?")
            .param(routeId)
            .query(Long.class)
            .single();
    }

    /** 예보 행에 남은 회수 결과 네 열. */
    private record StoredLabel(
        String scoringState,
        Long arrivalObservationId,
        Integer seatsOnArrival,
        Instant scoredAt
    ) {
    }

    private record StoredOutcome(
        int rowCount,
        int actualFullCount,
        double rawFullChanceSum,
        Instant settledThrough
    ) {
    }
}
