package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.processor.ArrivalLabel;
import com.gustler.backend.processor.ForecastSettlement;
import com.gustler.backend.processor.SameDayFullOutcomeCount;
import com.gustler.backend.processor.SameDayFullOutcomesService;
import com.gustler.backend.processor.SeatForecast;
import com.gustler.backend.processor.SeoulDay;
import com.gustler.backend.processor.SettledForecast;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
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
class JdbcSameDayFullOutcomesRepositoryTest {

    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int SEATS_LEFT = 12;
    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String LATER_CONTENT_DIGEST = "1".repeat(64);
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204000207 = "204000207";
    private static final int PASSED_STOP_ORDER = 6;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int STOPS_TO_TARGET = TARGET_STOP_ORDER - PASSED_STOP_ORDER;
    private static final int DEMAND_STATISTICS_REVISION = 3;
    private static final double RAW_FULL_CHANCE = 0.41;
    private static final double OTHER_RAW_FULL_CHANCE = 0.21;
    private static final int SEATS_ON_ARRIVAL_WHEN_FULL = 0;
    private static final int SEATS_ON_ARRIVAL_WHEN_NOT_FULL = 7;

    /** 판이 상류 응답을 받은 시각. 한국 시각 8월 19일 11시 14분이다. */
    private static final OffsetDateTime RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime ARRIVAL_RESPONSE_RECEIVED_AT =
        OffsetDateTime.parse("2026-08-19T11:20:31.402+09:00");
    private static final Instant ARRIVED_AT = ARRIVAL_RESPONSE_RECEIVED_AT.toInstant();
    private static final SeoulDay ARRIVAL_DAY = SeoulDay.containing(ARRIVED_AT);
    private static final Instant GENERATED_AT = Instant.parse("2026-08-19T02:14:05Z");
    private static final Instant SCORED_AT = Instant.parse("2026-08-19T02:25:00Z");

    @Autowired
    private JdbcSameDayFullOutcomesRepository repository;

    @Autowired
    private JdbcSeatForecastRepository seatForecastRepository;

    @Autowired
    private SameDayFullOutcomesService service;

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
        routeVersionId = insertRouteVersion(routeId, CONTENT_DIGEST, RESPONSE_RECEIVED_AT);
        insertRouteStops(routeVersionId);
        modelDeploymentId = insertModelDeployment();
        observationBatchId = insertObservationBatch(routeVersionId, "2026-08-19T11:14", RESPONSE_RECEIVED_AT);
        vehicleObservationId = insertObservation(observationBatchId, routeVersionId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
    }

    @Test
    void 만석으로_닫힌_예보를_더하면_집계_한_줄이_생긴다() {
        // when
        repository.add(settleAsFull(vehicleObservationId, RAW_FULL_CHANCE));

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 1, RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 자리가_남은_채_닫힌_예보를_더하면_건수만_늘고_만석_수는_안_는다() {
        // when
        repository.add(settleWithSeats(vehicleObservationId, RAW_FULL_CHANCE, SEATS_ON_ARRIVAL_WHEN_NOT_FULL));

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 0, RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 같은_예보_거리의_결과가_쌓이면_한_줄에_더해진다() {
        // given
        final long otherObservationId =
            insertObservation(observationBatchId, routeVersionId, VEHICLE_204000207, 1, PASSED_STOP_ORDER);

        // when
        repository.add(settleAsFull(vehicleObservationId, RAW_FULL_CHANCE));
        repository.add(settleWithSeats(otherObservationId, OTHER_RAW_FULL_CHANCE, SEATS_ON_ARRIVAL_WHEN_NOT_FULL));

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 2, 1, RAW_FULL_CHANCE + OTHER_RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 집계가_없을_때_정산분을_더하면_그_전에_닫힌_예보까지_센다() {
        // given 집계 테이블이 생기기 전에 닫힌 예보가 원본에 있다
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);
        final long otherObservationId =
            insertObservation(observationBatchId, routeVersionId, VEHICLE_204000207, 1, PASSED_STOP_ORDER);
        SettledForecast settledAfter =
            settleWithSeats(otherObservationId, OTHER_RAW_FULL_CHANCE, SEATS_ON_ARRIVAL_WHEN_NOT_FULL);

        // when
        service.record(List.of(settledAfter));

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 2, 1, RAW_FULL_CHANCE + OTHER_RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 덮어쓴_집계는_그대로_읽힌다() {
        // given
        SameDayFullOutcomeCount count = new SameDayFullOutcomeCount(STOPS_TO_TARGET, 5, 2, 1.7, ARRIVED_AT);

        // when
        repository.upsertCounts(routeId, ARRIVAL_DAY, List.of(count));

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(count);
    }

    @Test
    void 원본에서_세면_오늘_도착이_확인된_예보의_성적이_예보_거리마다_나온다() {
        // given
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);

        // when
        List<SameDayFullOutcomeCount> actual =
            repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT.plusSeconds(60));

        // then
        assertThat(actual).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 1, RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 기준_시각과_같은_순간에_도착한_것도_센다() {
        // given
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);

        // when
        List<SameDayFullOutcomeCount> actual = repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT);

        // then
        assertThat(actual).hasSize(1);
    }

    @Test
    void 기준_시각보다_뒤에_도착한_것은_안_센다() {
        // given
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);

        // when
        List<SameDayFullOutcomeCount> actual =
            repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT.minusSeconds(60));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 아직_도착이_확인_안_된_예보는_안_센다() {
        // given
        seatForecastRepository.save(List.of(forecastOf(vehicleObservationId, routeVersionId, RAW_FULL_CHANCE)));

        // when
        List<SameDayFullOutcomeCount> actual =
            repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT.plusSeconds(60));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_날짜의_도착은_안_센다() {
        // given
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);
        SeoulDay nextDay = SeoulDay.containing(ARRIVED_AT.plus(Duration.ofDays(1)));

        // when
        List<SameDayFullOutcomeCount> actual =
            repository.countFromSource(routeId, nextDay, ARRIVED_AT.plus(Duration.ofDays(1)));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 같은_노선의_다른_판본에서_난_예보도_한_성적으로_센다() {
        // given 첫 판본의 예보 하나와 개편된 판본의 예보 하나가 둘 다 도착까지 확인됐다
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);
        final long laterVersionId = insertLaterRouteVersion();
        final long laterObservationId = insertObservation(
            insertObservationBatch(laterVersionId, "2026-08-19T11:30", RESPONSE_RECEIVED_AT.plusMinutes(16)),
            laterVersionId, VEHICLE_204000206, 0, PASSED_STOP_ORDER);
        final long laterArrivalId = insertObservation(
            insertObservationBatch(laterVersionId, "2026-08-19T11:36", ARRIVAL_RESPONSE_RECEIVED_AT.plusMinutes(16)),
            laterVersionId, VEHICLE_204000206, 0, TARGET_STOP_ORDER);
        seatForecastRepository.save(List.of(forecastOf(laterObservationId, laterVersionId, OTHER_RAW_FULL_CHANCE)));
        seatForecastRepository.settle(List.of(new ForecastSettlement(
            laterObservationId, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(laterArrivalId, SEATS_ON_ARRIVAL_WHEN_NOT_FULL), SCORED_AT)));

        // when
        List<SameDayFullOutcomeCount> actual =
            repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT.plus(Duration.ofMinutes(20)));

        // then
        assertThat(actual).containsExactly(new SameDayFullOutcomeCount(
            STOPS_TO_TARGET, 2, 1, RAW_FULL_CHANCE + OTHER_RAW_FULL_CHANCE, ARRIVED_AT.plus(Duration.ofMinutes(16))));
    }

    @Test
    void 편도_제외로_판정_버전이_바뀌면_기존_집계와_예측값을_후보정에_재사용하지_않는다() {
        // given
        service.record(List.of(settleAsFull(vehicleObservationId, RAW_FULL_CHANCE)));
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).hasSize(1);

        // when
        jdbcClient.sql("UPDATE route SET quality_revision = quality_revision + 1 WHERE id = ?")
            .param(routeId).update();

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).isEmpty();
        assertThat(repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT)).isEmpty();
        assertThat(service.outcomesFor(routeId, ARRIVED_AT)).isEmpty();
    }

    @Test
    void 새_판정_버전의_결과를_더할_때_이전_버전의_건수는_합산하지_않는다() {
        // given
        repository.add(settleAsFull(vehicleObservationId, RAW_FULL_CHANCE));
        jdbcClient.sql("UPDATE route SET quality_revision = quality_revision + 1 WHERE id = ?")
            .param(routeId).update();
        long other = insertObservation(observationBatchId, routeVersionId, VEHICLE_204000207, 1, PASSED_STOP_ORDER);
        SettledForecast current = settleWithSeats(other, OTHER_RAW_FULL_CHANCE, SEATS_ON_ARRIVAL_WHEN_NOT_FULL);

        // when
        repository.add(current);

        // then
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).containsExactly(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 0, OTHER_RAW_FULL_CHANCE, ARRIVED_AT));
    }

    @Test
    void 집계를_다시_만들_때_제외된_편도의_예보는_세지_않는다() {
        // given
        settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);
        jdbcClient.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED' WHERE start_observation_id = ?")
            .param(vehicleObservationId).update();

        // when
        List<SameDayFullOutcomeCount> counts = repository.countFromSource(routeId, ARRIVAL_DAY, ARRIVED_AT);

        // then
        assertThat(counts).isEmpty();
    }

    @Test
    void 원본이_비어_있으면_같은_날짜와_품질_버전에서는_한번만_원본을_센다() {
        // given
        var observedRepository = spy(new JdbcSameDayFullOutcomesRepository(jdbcClient));
        var observedService = new SameDayFullOutcomesService(observedRepository);

        // when
        var first = observedService.outcomesFor(routeId, ARRIVED_AT);
        var second = observedService.outcomesFor(routeId, ARRIVED_AT.plusSeconds(10));

        // then
        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        verify(observedRepository, times(1)).countFromSource(routeId, ARRIVAL_DAY, ARRIVAL_DAY.end());
        assertThat(repository.findCounts(routeId, ARRIVAL_DAY)).singleElement()
            .extracting(SameDayFullOutcomeCount::rowCount).isEqualTo(0);
    }

    @Test
    void 빈_집계를_기록한_뒤_첫_실제_결과가_들어오면_원본_재집계_없이_한건을_더한다() {
        // given
        var observedRepository = spy(new JdbcSameDayFullOutcomesRepository(jdbcClient));
        var observedService = new SameDayFullOutcomesService(observedRepository);
        observedService.outcomesFor(routeId, ARRIVED_AT);
        var settled = settleAsFull(vehicleObservationId, RAW_FULL_CHANCE);

        // when
        observedService.record(List.of(settled));
        var outcomes = observedService.outcomesFor(routeId, ARRIVED_AT);

        // then
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(STOPS_TO_TARGET).rowCount()).isEqualTo(1);
        verify(observedRepository, times(1)).countFromSource(routeId, ARRIVAL_DAY, ARRIVAL_DAY.end());
    }

    private SettledForecast settleAsFull(
        final long observationId,
        final double rawFullChance
    ) {
        return settleWithSeats(observationId, rawFullChance, SEATS_ON_ARRIVAL_WHEN_FULL);
    }

    /** 예보를 쓰고, 다음 판에서 대상 정류소를 지난 관측으로 닫는다. 닫힌 결과 하나를 돌려준다. */
    private SettledForecast settleWithSeats(
        final long observationId,
        final double rawFullChance,
        final int seatsOnArrival
    ) {
        seatForecastRepository.save(List.of(forecastOf(observationId, routeVersionId, rawFullChance)));
        final long arrivalBatchId = insertObservationBatch(
            routeVersionId, "2026-08-19T11:20-" + observationId, ARRIVAL_RESPONSE_RECEIVED_AT);
        final long arrivalObservationId =
            insertObservation(arrivalBatchId, routeVersionId, jdbcClient.sql("SELECT vehicle_id FROM vehicle_observation WHERE id = ?")
                .param(observationId).query(String.class).single(), 0, TARGET_STOP_ORDER);
        return seatForecastRepository.settle(List.of(new ForecastSettlement(
            observationId, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalObservationId, seatsOnArrival), SCORED_AT))).getFirst();
    }

    private SeatForecast forecastOf(
        final long observationId,
        final long versionId,
        final double rawFullChance
    ) {
        return new SeatForecast(
            observationId, versionId, TARGET_STOP_ORDER, STOPS_TO_TARGET,
            modelDeploymentId, DEMAND_STATISTICS_REVISION, rawFullChance, 0.38, 12.5, GENERATED_AT);
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
        final long route,
        String contentDigest,
        OffsetDateTime validFrom
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(route, contentDigest, validFrom)
            .query(Long.class)
            .single();
    }

    private long insertLaterRouteVersion() {
        OffsetDateTime revisedAt = RESPONSE_RECEIVED_AT.plusMinutes(15);
        jdbcClient.sql("UPDATE route_version SET valid_to = ? WHERE id = ?")
            .params(revisedAt, routeVersionId)
            .update();
        final long laterVersionId = insertRouteVersion(routeId, LATER_CONTENT_DIGEST, revisedAt);
        insertRouteStops(laterVersionId);
        return laterVersionId;
    }

    private void insertRouteStops(
        final long versionId
    ) {
        for (int stopOrder : List.of(PASSED_STOP_ORDER, TARGET_STOP_ORDER)) {
            jdbcClient.sql("""
                    INSERT INTO route_stop (
                        route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(versionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP", true)
                .update();
        }
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
        final long versionId,
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
                batchId, versionId, sourceRowNumber,
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
}
