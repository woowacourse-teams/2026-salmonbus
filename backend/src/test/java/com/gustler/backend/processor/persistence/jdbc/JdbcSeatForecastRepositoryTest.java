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

    private long routeVersionId;
    private long modelDeploymentId;
    private long observationBatchId;
    private long vehicleObservationId;

    @BeforeEach
    void 노선_판본과_정류소와_모델과_관측을_먼저_저장한다() {
        final long routeId = insertRoute();
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
            routeVersionId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plusSeconds(60));

        // then
        assertThat(actual.get(STOPS_TO_TARGET))
            .isEqualTo(new SameDayFullOutcomes(1, 1, 0.41));
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
            routeVersionId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().minusSeconds(60));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 아직_도착이_확인_안_된_예보는_성적에_안_센다() {
        // given 회수를 안 한 예보다
        jdbcSeatForecastRepository.save(List.of(forecastOf(TARGET_STOP_ORDER, STOPS_TO_TARGET, GENERATED_AT)));

        // when
        Map<Integer, SameDayFullOutcomes> actual = jdbcSeatForecastRepository.readSameDayFullOutcomes(
            routeVersionId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plusSeconds(60));

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
            routeVersionId, ARRIVAL_RESPONSE_RECEIVED_AT.toInstant().plus(Duration.ofDays(1)));

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
        return jdbcClient.sql("""
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
            .single();
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
