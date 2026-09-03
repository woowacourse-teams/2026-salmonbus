package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.StopDemandHourlyTotals;
import com.gustler.backend.processor.StopDemandStatisticsJob;
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

/**
 * seed 가 서 있을 때 시간별 합을 역사와 실시간에서 함께 읽는지 본다.
 *
 * <p>seed 표는 이관 도구의 Flyway 가 만든다. worker 테스트에는 V1~V12 만 적용돼서 여기서 직접
 * 세운다. 도구가 나중에 표를 바꾸면 이 테스트가 아니라 도구 쪽 통합 테스트가 먼저 깨진다.
 *
 * <p>저장소를 주입받지 않고 테스트마다 새로 만든다. seed 표가 있는지 본 결과를 인스턴스가
 * 기억해서, 컨텍스트를 함께 쓰는 다른 테스트에 그 기억이 넘어가면 안 된다.
 */
@IntegrationTest
@Transactional
class JdbcStopDemandStatisticsRepositorySeedTest {

    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String COLLECTION_STRATEGY_VERSION = "adaptive-kst-v1.0.1";
    private static final String OUTCOME_SUCCESS_ROWS = "SUCCESS_ROWS";
    private static final int RUNNING_STATE_DEPARTED = 2;

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String VEHICLE_BEFORE_CUTOVER = "204000206";
    private static final String VEHICLE_ON_CUTOVER = "204000311";
    private static final String VEHICLE_AFTER_CUTOVER = "204003542";

    private static final int FIRST_STOP_ORDER = 1;
    private static final int LAST_STOP_ORDER = 13;
    private static final int TARGET_STOP_ORDER = 9;
    private static final int NEXT_STOP_AHEAD = 1;

    /**
     * 실시간 쪽을 가르는 시각. 예측 관측이 실린 판의 응답 시각을 이것과 견준다.
     *
     * <p>경계가 닫힌 쪽이라 이 시각에 받은 판은 센다. 역사 합이 이 시각 앞을 이미 담고 있어서,
     * 양쪽을 다 세면 그 순간의 라벨이 두 번 들어간다.
     */
    private static final Instant CUTOVER_AT = Instant.parse("2026-08-19T02:00:00Z");

    /** 예측 판은 도착 5분 전이다. 셋 다 도착 시각이 UTC 02시라 한 시각 묶음으로 모인다. */
    private static final int MINUTES_BEFORE_ARRIVAL = 5;
    private static final OffsetDateTime ARRIVED_BEFORE_CUTOVER =
        OffsetDateTime.parse("2026-08-19T11:02:30+09:00");
    private static final OffsetDateTime ARRIVED_ON_CUTOVER =
        OffsetDateTime.parse("2026-08-19T11:05:00+09:00");
    private static final OffsetDateTime ARRIVED_AFTER_CUTOVER =
        OffsetDateTime.parse("2026-08-19T11:50:00+09:00");
    private static final Instant ARRIVED_HOUR_START = Instant.parse("2026-08-19T02:00:00Z");

    private static final Instant SCORED_AT = Instant.parse("2026-08-19T03:00:00Z");
    private static final Instant DATA_UNTIL = Instant.parse("2026-08-19T04:00:00Z");
    private static final OffsetDateTime GENERATED_AT = OffsetDateTime.parse("2026-08-19T10:57:31+09:00");

    /** 예측 잔여석이 그 차량의 최대라 정원이 40 으로 잡힌다. 도착 20 이면 채운 비율이 0.5 다. */
    private static final int SEATS_ON_PREDICTION = 40;
    private static final int SEATS_ON_ARRIVAL = 20;
    private static final double LIVE_FILL_RATE = 0.5;
    private static final double LIVE_NET_BOARDING = 20.0;
    private static final double LIVE_CAPACITY = 40.0;
    private static final int LIVE_SAMPLE_COUNT = 1;

    /** 역사 쪽 한 줄. 표본 넷을 담은 합이다. */
    private static final double SEED_FILL_RATE = 2.0;
    private static final double SEED_NET_BOARDING = 100.0;
    private static final double SEED_CAPACITY = 200.0;
    private static final int SEED_SAMPLE_COUNT = 4;

    private static final double SEAT_FULL_CHANCE_RAW = 0.41;
    private static final double SEAT_FULL_CHANCE = 0.38;
    private static final double EXPECTED_SEATS = 12.5;
    private static final int DEMAND_STATISTICS_REVISION = 3;
    private static final int SOURCE_ROW_NUMBER = 0;

    @Autowired
    private JdbcClient jdbcClient;

    private JdbcStopDemandStatisticsRepository repository;
    private long routeVersionId;
    private long modelDeploymentId;

    @BeforeEach
    void 노선과_모델과_seed_표를_먼저_세운다() {
        repository = new JdbcStopDemandStatisticsRepository(jdbcClient);
        routeVersionId = insertRouteVersion(insertRoute());
        for (int stopOrder = FIRST_STOP_ORDER; stopOrder <= LAST_STOP_ORDER; stopOrder++) {
            insertRouteStop(stopOrder);
        }
        modelDeploymentId = insertModelDeployment();
        createSeedSchema();
    }

    @Test
    void seed_가_서_있으면_역사_합과_경계_뒤_실시간_합을_더한다() {
        // given
        final UUID seedImportId = insertAppliedSeedImport();
        insertSeedHourlyTotal(seedImportId);
        insertSettledLabel(VEHICLE_AFTER_CUTOVER, ARRIVED_AFTER_CUTOVER);

        // when
        List<StopDemandHourlyTotals> actual = repository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement().isEqualTo(new StopDemandHourlyTotals(
            TARGET_STOP_ORDER,
            ARRIVED_HOUR_START,
            SEED_FILL_RATE + LIVE_FILL_RATE,
            SEED_NET_BOARDING + LIVE_NET_BOARDING,
            SEED_CAPACITY + LIVE_CAPACITY,
            SEED_SAMPLE_COUNT + LIVE_SAMPLE_COUNT));
    }

    /** 경계 앞은 역사 합이 이미 담고 있다. 실시간에서 또 세면 그 라벨이 두 번 들어간다. */
    @Test
    void 경계_앞_실시간_라벨은_역사_합과_겹쳐서_안_센다() {
        // given
        final UUID seedImportId = insertAppliedSeedImport();
        insertSeedHourlyTotal(seedImportId);
        insertSettledLabel(VEHICLE_BEFORE_CUTOVER, ARRIVED_BEFORE_CUTOVER);

        // when
        List<StopDemandHourlyTotals> actual = repository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement().isEqualTo(new StopDemandHourlyTotals(
            TARGET_STOP_ORDER,
            ARRIVED_HOUR_START,
            SEED_FILL_RATE,
            SEED_NET_BOARDING,
            SEED_CAPACITY,
            SEED_SAMPLE_COUNT));
    }

    @Test
    void 경계와_같은_시각에_받은_판의_라벨은_센다() {
        // given
        final UUID seedImportId = insertAppliedSeedImport();
        insertSeedHourlyTotal(seedImportId);
        insertSettledLabel(VEHICLE_ON_CUTOVER, ARRIVED_ON_CUTOVER);

        // when
        List<StopDemandHourlyTotals> actual = repository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement()
            .extracting(StopDemandHourlyTotals::sampleCount)
            .isEqualTo(SEED_SAMPLE_COUNT + LIVE_SAMPLE_COUNT);
    }

    /** seed 표만 있고 선 세대가 없으면 예전 길로 간다. 역사 합이 없으니 더할 것도 없다. */
    @Test
    void 선_seed_가_없으면_실시간_합만_읽는다() {
        // given
        insertSettledLabel(VEHICLE_BEFORE_CUTOVER, ARRIVED_BEFORE_CUTOVER);

        // when
        List<StopDemandHourlyTotals> actual = repository.readHourlyTotals(routeVersionId, DATA_UNTIL);

        // then
        assertThat(actual).singleElement().isEqualTo(new StopDemandHourlyTotals(
            TARGET_STOP_ORDER,
            ARRIVED_HOUR_START,
            LIVE_FILL_RATE,
            LIVE_NET_BOARDING,
            LIVE_CAPACITY,
            LIVE_SAMPLE_COUNT));
    }

    /**
     * 도구 Flyway 의 V3 에서 조회가 읽는 열과 view 정의만 옮겨 왔다.
     *
     * <p>CHECK 은 안 옮긴다. 여기서 보는 것은 합산이지 도구가 넣는 값의 범위가 아니다.
     */
    private void createSeedSchema() {
        jdbcClient.sql("""
            CREATE TABLE stop_demand_seed_import (
                id                   uuid        NOT NULL,
                calculation_version  varchar(40) NOT NULL,
                final_cutover_at     timestamptz NOT NULL,
                status               varchar(16) NOT NULL,
                CONSTRAINT pk_stop_demand_seed_import PRIMARY KEY (id)
            )
            """).update();
        jdbcClient.sql("""
            CREATE TABLE stop_demand_seed_hourly_total (
                seed_import_id      uuid        NOT NULL,
                route_version_id    bigint      NOT NULL,
                stop_order          integer     NOT NULL,
                arrival_date_kst    date        NOT NULL,
                arrival_hour_start  timestamptz NOT NULL,
                fill_rate_total     numeric     NOT NULL,
                net_boarding_total  numeric     NOT NULL,
                capacity_total      numeric     NOT NULL,
                sample_count        integer     NOT NULL,
                CONSTRAINT pk_stop_demand_seed_hourly_total PRIMARY KEY (
                    seed_import_id, route_version_id, stop_order, arrival_hour_start)
            )
            """).update();
        jdbcClient.sql("""
            CREATE VIEW active_stop_demand_seed_hourly_total AS
            SELECT hourly.route_version_id,
                   hourly.stop_order,
                   hourly.arrival_date_kst,
                   hourly.arrival_hour_start,
                   hourly.fill_rate_total,
                   hourly.net_boarding_total,
                   hourly.capacity_total,
                   hourly.sample_count,
                   seed.calculation_version,
                   seed.final_cutover_at
            FROM stop_demand_seed_hourly_total hourly
            JOIN stop_demand_seed_import seed ON seed.id = hourly.seed_import_id
            WHERE seed.status = 'APPLIED'
            """).update();
    }

    private UUID insertAppliedSeedImport() {
        final UUID seedImportId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO stop_demand_seed_import (
                    id, calculation_version, final_cutover_at, status
                ) VALUES (?, ?, ?, 'APPLIED')
                """)
            .params(
                seedImportId,
                StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION,
                CUTOVER_AT.atOffset(ZoneOffset.UTC))
            .update();
        return seedImportId;
    }

    private void insertSeedHourlyTotal(
        UUID seedImportId
    ) {
        jdbcClient.sql("""
                INSERT INTO stop_demand_seed_hourly_total (
                    seed_import_id, route_version_id, stop_order, arrival_date_kst,
                    arrival_hour_start, fill_rate_total, net_boarding_total,
                    capacity_total, sample_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                seedImportId, routeVersionId, TARGET_STOP_ORDER,
                ARRIVED_HOUR_START.atOffset(ZoneOffset.UTC).toLocalDate(),
                ARRIVED_HOUR_START.atOffset(ZoneOffset.UTC),
                SEED_FILL_RATE, SEED_NET_BOARDING, SEED_CAPACITY, SEED_SAMPLE_COUNT)
            .update();
    }

    private void insertSettledLabel(
        String vehicleId,
        OffsetDateTime arrivedAt
    ) {
        final long predictionObservationId = insertObservation(
            vehicleId, TARGET_STOP_ORDER - NEXT_STOP_AHEAD, SEATS_ON_PREDICTION,
            arrivedAt.minusMinutes(MINUTES_BEFORE_ARRIVAL));
        final long arrivalObservationId = insertObservation(
            vehicleId, TARGET_STOP_ORDER, SEATS_ON_ARRIVAL, arrivedAt);
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                    model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats, generated_at,
                    scoring_state, arrival_observation_id, seats_on_arrival, scored_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?, ?)
                """)
            .params(
                predictionObservationId, TARGET_STOP_ORDER, routeVersionId, NEXT_STOP_AHEAD,
                modelDeploymentId, DEMAND_STATISTICS_REVISION,
                SEAT_FULL_CHANCE_RAW, SEAT_FULL_CHANCE, EXPECTED_SEATS, GENERATED_AT,
                arrivalObservationId, SEATS_ON_ARRIVAL, SCORED_AT.atOffset(ZoneOffset.UTC))
            .update();
    }

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
                RUNNING_STATE_DEPARTED, remainingSeats)
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
                NORMALIZATION_VERSION, COLLECTION_STRATEGY_VERSION)
            .query(Long.class)
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
            .params(routeId, CONTENT_DIGEST, ARRIVED_BEFORE_CUTOVER)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final int stopOrder
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, true)
                """)
            .params(routeVersionId, stopOrder, stopIdOf(stopOrder), "정류소 " + stopOrder, "UP")
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
                UUID.randomUUID(),
                "2026-08-19-1", "seat-full-chance", "1.4.0", CONTENT_DIGEST,
                "SEAT_FULL_CHANCE_V1", "CALCULATION_V1", CONTENT_DIGEST,
                ARRIVED_BEFORE_CUTOVER, "ACTIVE")
            .query(Long.class)
            .single();
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500%04d".formatted(stopOrder);
    }
}
