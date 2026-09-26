package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.processor.ArrivalLabel;
import com.gustler.backend.processor.StopDemandAccumulationWriter;
import com.gustler.backend.processor.StopDemandRebuildWriter;
import com.gustler.backend.processor.TripQualityRepository;
import com.gustler.backend.processor.StopDemandPipeline;
import com.gustler.backend.processor.StopDemandAggregator;
import com.gustler.backend.processor.StopDemandStatisticsJob;
import com.gustler.backend.processor.StopDemandGeneration;
import com.gustler.backend.processor.TimeSlot;
import java.time.Clock;
import java.time.ZoneId;
import static org.mockito.Mockito.when;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.gustler.backend.processor.ForecastSettlement;
import com.gustler.backend.processor.PendingForecast;
import com.gustler.backend.processor.SeatForecast;
import com.gustler.backend.processor.SettledForecast;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Autowired
    private StopDemandAccumulationWriter accumulation;

    @Autowired private StopDemandRebuildWriter rebuild;
    @Autowired private TripQualityRepository quality;
    @Autowired private StopDemandPipeline pipeline;
    @MockitoBean private Clock clock;
    private static final Instant PIPELINE_NOW = Instant.parse("2026-09-25T00:00:00Z");

    private long routeId;
    private long routeVersionId;
    private long modelDeploymentId;
    private long observationBatchId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류소와_모델과_관측을_먼저_저장한다() {
        when(clock.instant()).thenReturn(PIPELINE_NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
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
    void 만석으로_회수하면_닫힌_예보의_노선과_거리와_확률과_도착_시각을_돌려준다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, SEATS_ON_ARRIVAL_WHEN_FULL),
            SCORED_AT)));

        // then
        assertThat(actual).containsExactly(new SettledForecast(
            routeId, STOPS_TO_TARGET, 0.41, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant(), SEATS_ON_ARRIVAL_WHEN_FULL));
    }

    @Test
    void 좌석_결측이나_건너뜀으로_회수한_예보는_돌려주지_않는다() {
        // given
        final long arrivalObservationId = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(
            forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT),
            forecastOf(NEXT_TARGET_STOP_ORDER, STOPS_TO_NEXT_TARGET, NEXT_GENERATED_AT)));

        // when
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(
            new ForecastSettlement(
                vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.SeatMissing(arrivalObservationId), SCORED_AT),
            new ForecastSettlement(
                vehicleObservationId, NEXT_TARGET_STOP_ORDER, new ArrivalLabel.Skipped(), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 이미_닫힌_예보를_다시_회수하면_아무것도_돌려주지_않는다() {
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
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(settlement));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 정산과_함께_통계에_쓸_원본_값과_도착시각을_기록한다() {
        long arrival = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));

        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
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
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        var settlement = new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT);

        jdbcSeatForecastRepository.settle(List.of(settlement));
        jdbcSeatForecastRepository.settle(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);

        // 누적 완료 후 처리 대상을 지운 경우에도 정산 상태가 재기록을 막는다.
        jdbcClient.sql("DELETE FROM stop_demand_pending_sample WHERE prediction_observation_id = ?")
            .param(vehicleObservationId).update();
        jdbcSeatForecastRepository.settle(List.of(settlement));
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 처리_대상_저장에_실패하면_정산도_취소된다() {
        long arrival = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        // 이 테스트 transaction 안에서만 INSERT 실패를 유발한다.
        jdbcClient.sql("""
            ALTER TABLE stop_demand_pending_sample ADD CONSTRAINT test_reject_sample
            CHECK (prediction_remaining_seats < 0)
            """).update();
        jdbcClient.sql("SAVEPOINT before_settlement").update();

        assertThatThrownBy(() -> jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT))))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_settlement").update();
        assertThat(readStoredLabel(TARGET_STOP_ORDER)).isEqualTo(new StoredLabel("PENDING", null, null, null));
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 후속_작업의_롤백은_정산과_처리_대상을_함께_되돌린다() {
        long arrival = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        var settlement = new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT);
        jdbcClient.sql("SAVEPOINT before_settlement").update();
        jdbcSeatForecastRepository.settle(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_settlement").update();

        assertThat(readStoredLabel(TARGET_STOP_ORDER)).isEqualTo(new StoredLabel("PENDING", null, null, null));
        assertThat(pendingSampleCount()).isZero();
        jdbcSeatForecastRepository.settle(List.of(settlement));
        assertThat(pendingSampleCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"distant", "non_boarding", "missing_seats"})
    void 통계_입력_조건을_벗어난_정산은_처리_대상에_넣지_않는다(String excludedReason) {
        long arrival = insertArrivalObservation();
        int target = excludedReason.equals("distant") ? NEXT_TARGET_STOP_ORDER : TARGET_STOP_ORDER;
        int horizon = excludedReason.equals("distant") ? STOPS_TO_NEXT_TARGET : 1;
        jdbcSeatForecastRepository.save(List.of(forecastOf(target, horizon, GENERATED_AT)));
        if (excludedReason.equals("non_boarding")) {
            jdbcClient.sql("UPDATE route_stop SET boarding_allowed = false WHERE route_version_id = ? AND stop_order = ?")
                .params(routeVersionId, target).update();
        }
        if (excludedReason.equals("missing_seats")) {
            jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats = NULL, seat_unknown_reason = 'NOT_REPORTED' WHERE id = ?")
                .param(vehicleObservationId).update();
        }

        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, target, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        assertThat(readStoredLabel(target).scoringState()).isEqualTo("SETTLED");
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 누적은_한_페이지씩_진행하고_재시도해도_합계가_늘지_않는다() {
        givenStatisticsSample();
        // 한 SQL 페이지보다 큰 합성 입력. 처리량 제한과 이어하기를 확인한다.
        jdbcClient.sql("""
            INSERT INTO stop_demand_pending_sample(route_version_id, prediction_observation_id,
                arrival_observation_id, vehicle_id, target_stop_order, arrived_at, scored_at,
                prediction_remaining_seats, arrival_remaining_seats)
            SELECT route_version_id, prediction_observation_id, arrival_observation_id, vehicle_id,
                target_stop_order, arrived_at, scored_at, prediction_remaining_seats, arrival_remaining_seats
            FROM stop_demand_pending_sample CROSS JOIN generate_series(1, 128)
            """).update();

        var first = accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(first.applied()).isEqualTo(128);
        assertThat(pendingSampleCount()).isEqualTo(1);
        var second = accumulation.apply(routeVersionId, VEHICLE_204000206, first.nextInputId(), Long.MAX_VALUE, SCORED_AT);
        assertThat(second.applied()).isEqualTo(1);
        accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(129);
        assertThat(jdbcClient.sql("SELECT net_boarding_sum FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(129L * SEATS_LEFT);
    }

    @Test
    void 하차로_잔여석이_늘어도_원합은_기존_통계와_같은_값을_복원한다() {
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=20 WHERE id=?").param(arrival).update();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 20), SCORED_AT)));
        var original = new JdbcStopDemandStatisticsRepository(jdbcClient)
            .readHourlyTotals(routeVersionId, SCORED_AT).getFirst();

        accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        var sums = jdbcClient.sql("SELECT sample_count, arrival_seats_sum, net_boarding_sum FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        assertThat(sums).containsExactly(1, 20, -8);
        assertThat(sums[0] - sums[1] / 20.0).isEqualTo(original.fillRateTotal());
        assertThat((double) sums[2]).isEqualTo(original.netBoardingTotal());
        assertThat(sums[0] * 20.0).isEqualTo(original.capacityTotal());
    }

    @Test
    void 누적_롤백은_합계와_대기_자료를_함께_되돌린다() {
        givenStatisticsSample();
        jdbcClient.sql("SAVEPOINT before_accumulation").update();
        accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(pendingSampleCount()).isZero();

        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_accumulation").update();

        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
    }

    @Test
    void 누적_직전_품질이_바뀌면_자료를_남기고_정정을_요청한다() {
        givenStatisticsSample();
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();

        var result = accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);

        assertThat(result.waitingForRebuild()).isTrue();
        assertThat(result.applied()).isZero();
        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_rebuild_request WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        assertThat(accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT).selected()).isZero();
    }

    @Test
    void 고정한_입력_범위나_기준시각_밖의_자료는_다음_처리를_위해_남긴다() {
        givenStatisticsSample();
        long id = jdbcClient.sql("SELECT id FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(vehicleObservationId).query(Long.class).single();

        assertThat(accumulation.apply(routeVersionId, VEHICLE_204000206, 0, id - 1, SCORED_AT).applied()).isZero();
        assertThat(accumulation.apply(routeVersionId, VEHICLE_204000206, 0, id, SCORED_AT.minusSeconds(1)).applied()).isZero();
        assertThat(pendingSampleCount()).isEqualTo(1);
        assertThat(accumulation.apply(routeVersionId, VEHICLE_204000206, 0, id, SCORED_AT).applied()).isEqualTo(1);
    }

    @Test
    void 정정은_이상_편도의_기여분을_제거하고_빈_완료도_표현한다() {
        givenStatisticsSample();
        accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE start_observation_id=?")
            .param(vehicleObservationId).update();
        quality.requestStatisticsRebuild(routeVersionId, "");

        finishRebuild("");

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT initialized FROM stop_demand_baseline WHERE route_version_id=?")
            .param(routeVersionId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 정정_시작_뒤의_정산은_이번_교체에서_제외하고_다음_누적에_한번만_쓴다() {
        givenStatisticsSample();
        long batch = insertObservationBatch("late-source", RESPONSE_RECEIVED_AT.plusSeconds(1));
        long source = insertObservation(batch, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
        jdbcSeatForecastRepository.save(List.of(new SeatForecast(source, routeVersionId, TARGET_STOP_ORDER, 1,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, 0.41, 0.38, 12.5, GENERATED_AT)));
        quality.requestStatisticsRebuild(routeVersionId, VEHICLE_204000206);
        rebuild.step(routeVersionId, VEHICLE_204000206); // 범위 고정
        long arrival = jdbcClient.sql("SELECT arrival_observation_id FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(vehicleObservationId).query(Long.class).single();
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            source, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        finishRebuild(VEHICLE_204000206);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        accumulation.apply(routeVersionId, VEHICLE_204000206, 0, Long.MAX_VALUE, SCORED_AT);
        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void 정정_페이지의_중간_실패와_재요청에도_합계가_중복되지_않는다() {
        givenStatisticsSample();
        quality.requestStatisticsRebuild(routeVersionId, VEHICLE_204000206);
        rebuild.step(routeVersionId, VEHICLE_204000206);
        jdbcClient.sql("SAVEPOINT before_rebuild_page").update();
        rebuild.step(routeVersionId, VEHICLE_204000206);
        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_rebuild_page").update();
        rebuild.step(routeVersionId, VEHICLE_204000206);
        quality.requestStatisticsRebuild(routeVersionId, VEHICLE_204000206);

        finishRebuild(VEHICLE_204000206);

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(1);
        assertThat(pendingSampleCount()).isZero();
    }

    private void finishRebuild(String vehicle) {
        for (int i = 0; i < 100; i++) {
            if (!rebuild.step(routeVersionId, vehicle)) { return; }
        }
        throw new AssertionError("정정이 100번의 작은 처리 안에 완료되지 않음");
    }

    @Test
    void 초기_이관_중에는_기존_정상_통계를_쓰고_완성된_동일_결과로_교체한다() {
        givenStatisticsSample();
        var statistics = new JdbcStopDemandStatisticsRepository(jdbcClient);
        var expected = StopDemandAggregator.aggregate(statistics.readHourlyTotals(routeVersionId, SCORED_AT), clock);
        statistics.append(new StopDemandGeneration(routeVersionId, StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION,
            1, SCORED_AT, SCORED_AT, expected));

        pipeline.step(routeVersionId);
        assertThat(statistics.readAsOf(routeVersionId, TimeSlot.OTHER,
            StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION, PIPELINE_NOW).revision()).isEqualTo(1);
        finishPipeline();

        var actual = statistics.readAsOf(routeVersionId, TimeSlot.OTHER,
            StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION, PIPELINE_NOW);
        assertThat(actual.revision()).isEqualTo(2);
        assertThat(actual.cells()).containsExactly(expected.getFirst().cell());
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 누적_세대의_입력을_고정하면_도중에_정산된_자료는_다음_세대에서만_반영된다() {
        givenStatisticsSample();
        finishPipeline();
        when(clock.instant()).thenReturn(PIPELINE_NOW.plusSeconds(21600));
        addLateSample("before-capture",1);
        for(int i=0;i<100;i++) {
            if(pipeline.step(routeVersionId).status().equals("STARTED")) { break; }
            if(i==99) { throw new AssertionError("통계 범위 고정에 도달하지 못함"); }
        }
        addLateSample("after-capture",2);

        finishPipeline();

        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isEqualTo(1);
        when(clock.instant()).thenReturn(PIPELINE_NOW.plusSeconds(43200));
        finishPipeline();
        assertThat(jdbcClient.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void 발행_도중_실패하면_통계와_완료_표시가_함께_취소된다() {
        givenStatisticsSample();
        for(int i=0;i<100;i++) {
            pipeline.step(routeVersionId);
            var phases=jdbcClient.sql("SELECT phase FROM stop_demand_run WHERE route_version_id=?")
                .param(routeVersionId).query(String.class).list();
            if(phases.contains("PUBLISH")) { break; }
            if(i==99) { throw new AssertionError("발행 단계에 도달하지 못함"); }
        }
        jdbcClient.sql("ALTER TABLE stop_demand_publication ADD CONSTRAINT test_reject_publication CHECK(revision<0)").update();
        jdbcClient.sql("SAVEPOINT before_publication").update();

        assertThatThrownBy(()->pipeline.step(routeVersionId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient.sql("ROLLBACK TO SAVEPOINT before_publication").update();

        assertThat(jdbcClient.sql("SELECT count(*) FROM stop_demand_statistics WHERE route_version_id=?")
            .param(routeVersionId).query(Integer.class).single()).isZero();
        jdbcClient.sql("ALTER TABLE stop_demand_publication DROP CONSTRAINT test_reject_publication").update();
        assertThat(pipeline.step(routeVersionId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void 빈_세대도_완료를_표현하고_완료_이전_관측에는_새_세대를_노출하지_않는다() {
        finishPipeline();
        var statistics=new JdbcStopDemandStatisticsRepository(jdbcClient);
        var ready=statistics.readAsOf(routeVersionId,TimeSlot.MORNING,
            StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION,PIPELINE_NOW);
        assertThat(ready.revision()).isEqualTo(1);
        assertThat(ready.cells()).isEmpty();
        jdbcClient.sql("UPDATE stop_demand_publication SET computed_at=? WHERE route_version_id=?")
            .params(PIPELINE_NOW.plusSeconds(30).atOffset(java.time.ZoneOffset.UTC),routeVersionId).update();
        assertThat(statistics.readAsOf(routeVersionId,TimeSlot.MORNING,
            StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION,PIPELINE_NOW).revision()).isZero();
    }

    @Test
    void 여러_페이지와_날짜의_통계도_전체_SQL의_날짜_균등가중_결과와_같다() {
        for(int stop=11;stop<=80;stop++) { insertRouteStop(stop); }
        for(int day=0;day<2;day++) {
            for(int stop=11;stop<=80;stop++) {
                addStatisticsAt("page-"+day+"-"+stop,stop,
                    OffsetDateTime.parse("2026-08-20T07:05:00+09:00").plusDays(day),
                    day==0 ? 24 : 40,day==0 ? 6 : 20);
            }
        }
        addStatisticsAt("extra-day-two",11,OffsetDateTime.parse("2026-08-21T07:10:00+09:00"),40,10);
        var repository=new JdbcStopDemandStatisticsRepository(jdbcClient);
        var expected=StopDemandAggregator.aggregate(repository.readHourlyTotals(routeVersionId,PIPELINE_NOW),clock);

        finishPipeline();

        var actual=repository.readAsOf(routeVersionId,TimeSlot.MORNING,
            StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION,PIPELINE_NOW);
        assertThat(actual.cells()).hasSize(70);
        for(var cell:actual.cells()) {
            var reference=expected.stream().filter(x->x.cell().stopOrder()==cell.stopOrder()).findFirst().orElseThrow().cell();
            assertThat(cell.sampleCount()).isEqualTo(reference.sampleCount());
            assertThat(cell.dayCount()).isEqualTo(reference.dayCount());
            assertThat(cell.averageFillRate()).isCloseTo(reference.averageFillRate(),org.assertj.core.data.Offset.offset(1e-12));
            assertThat(cell.averageNetBoardingRate()).isCloseTo(reference.averageNetBoardingRate(),org.assertj.core.data.Offset.offset(1e-12));
        }
        assertThat(actual.cells().getFirst().averageFillRate()).isCloseTo(0.7375,org.assertj.core.data.Offset.offset(1e-12));
    }

    private void addStatisticsAt(String key,int stop,OffsetDateTime at,int before,int after) {
        long sourceBatch=insertObservationBatch(key+"-source",at.minusSeconds(15));
        long source=insertObservation(sourceBatch,"synthetic-bus",0,stop-1);
        long arrivalBatch=insertObservationBatch(key+"-arrival",at);
        long arrival=insertObservation(arrivalBatch,"synthetic-bus",0,stop);
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=? WHERE id=?").params(before,source).update();
        jdbcClient.sql("UPDATE vehicle_observation SET remaining_seats=? WHERE id=?").params(after,arrival).update();
        jdbcSeatForecastRepository.save(List.of(new SeatForecast(source,routeVersionId,stop,1,
            modelDeploymentId,DEMAND_STATISTICS_REVISION,0.41,0.38,12.5,at.minusSeconds(14).toInstant())));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(source,stop,
            new ArrivalLabel.Settled(arrival,after),at.plusSeconds(60).toInstant())));
    }

    private void finishPipeline() {
        for(int i=0;i<200;i++) {
            if(pipeline.step(routeVersionId).status().equals("COMPLETED")) { return; }
        }
        throw new AssertionError("통계가 200번의 작은 처리 안에 완료되지 않음");
    }

    private void addLateSample(String key,int seconds) {
        long batch=insertObservationBatch(key,RESPONSE_RECEIVED_AT.plusSeconds(seconds));
        long source=insertObservation(batch,VEHICLE_204000206,0,PASSED_STOP_ORDER);
        jdbcSeatForecastRepository.save(List.of(new SeatForecast(source,routeVersionId,TARGET_STOP_ORDER,1,
            modelDeploymentId,DEMAND_STATISTICS_REVISION,0.41,0.38,12.5,GENERATED_AT)));
        long arrival=jdbcClient.sql("SELECT arrival_observation_id FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?")
            .params(vehicleObservationId,TARGET_STOP_ORDER).query(Long.class).single();
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(source,TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrival,0),SCORED_AT)));
    }

    private void givenStatisticsSample() {
        long arrival = insertArrivalObservation();
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, 1, GENERATED_AT)));
        jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));
    }

    private int pendingSampleCount() {
        return jdbcClient.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE prediction_observation_id = ?")
            .param(vehicleObservationId).query(Integer.class).single();
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

    /** 예보를 낸 뒤 다음 판에서 대상 정류소를 지난 그 차량의 관측. */
    @Test
    void 이전_판정_버전의_예보는_도착_좌석을_저장하되_후보정_증분에는_포함하지_않는다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE route SET quality_revision = quality_revision + 1 WHERE id = ?")
            .param(routeId).update();

        // when
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM seat_forecast WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbcClient.sql("SELECT seats_on_arrival FROM seat_forecast WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(Integer.class).single()).isZero();
        // 6시간 통계는 후보정과 달리 예보의 이전 quality_revision 자체를 제외하지 않는다.
        assertThat(pendingSampleCount()).isEqualTo(1);
    }

    @Test
    void 편도가_제외된_뒤에는_도착_결과와_후보정_증분을_저장하지_않는다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED' WHERE start_observation_id = ?")
            .param(vehicleObservationId).update();

        // when
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM seat_forecast WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(pendingSampleCount()).isZero();
    }

    @Test
    void 다른_방향의_도착_관측으로_예보를_닫지_않는다() {
        // given
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));
        long arrival = insertArrivalObservation();
        jdbcClient.sql("UPDATE route_version SET turn_sequence = 9 WHERE id = ?")
            .param(routeVersionId).update();

        // when
        List<SettledForecast> actual = jdbcSeatForecastRepository.settle(List.of(new ForecastSettlement(
            vehicleObservationId, TARGET_STOP_ORDER, new ArrivalLabel.Settled(arrival, 0), SCORED_AT)));

        // then
        assertThat(actual).isEmpty();
        assertThat(jdbcClient.sql("SELECT scoring_state FROM seat_forecast WHERE vehicle_observation_id = ?")
            .param(vehicleObservationId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(pendingSampleCount()).isZero();
    }

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
