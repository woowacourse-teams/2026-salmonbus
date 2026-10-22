package com.gustler.backend.api.vehicle.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.support.FixedDaytimeClockConfig;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
@Import(FixedDaytimeClockConfig.class)
class LiveVehicleApiContractTest {
    private static final String ROUTE_ID = "204000057";
    private static final OffsetDateTime NOW = OffsetDateTime.parse(
        "2026-08-27T12:00:00+09:00"
    );
    private static final String CONTENT_DIGEST = "5".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String STRATEGY_VERSION = "adaptive-kst-v1.0.1";
    private static final int RUNNING_STATE_ARRIVING = 1;
    private static final String SEATS_REPORTED_UNKNOWN = "REPORTED_UNKNOWN";
    private static final String SEATS_NOT_REPORTED = "NOT_REPORTED";
    private static final DateTimeFormatter JSON_TIME = DateTimeFormatter.ofPattern(
        "uuuu-MM-dd'T'HH:mm:ssXXX"
    );

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(applicationContext)
            .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"above_range", "excluded_trip", "unconfirmed_trip", "investigating"})
    void 품질이_확인되지_않은_잔여석은_숨기고_차량_위치는_유지한다(String reason) throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        final long batch = insertSuccessfulBatch(route, NOW.minusSeconds(20), "SUCCESS_ROWS", 2);
        int seats = 40;
        if (reason.equals("above_range")) {
            seats = 82;
        }
        insertObservation(batch, route, 0, "test-bus", 5, "205000005", 2, seats);
        insertObservation(batch, route, 1, "normal-bus", 5, "205000005", 2, 12);
        final long observation =
            jdbcClient.sql("SELECT id FROM vehicle_observation WHERE observation_batch_id=? AND source_row_number=0")
                .param(batch)
                .query(Long.class)
                .single();
        if (reason.equals("excluded_trip") || reason.equals("unconfirmed_trip")) {
            ConfirmedTripFixture.include(jdbcClient, observation);
            jdbcClient.sql("UPDATE vehicle_one_way_trip SET status=? WHERE start_observation_id=?")
                .params(tripState(reason), observation).update();
        }
        if (reason.equals("investigating")) {
            jdbcClient.sql("INSERT INTO trip_quality_rebuild(route_version_id, vehicle_id, until_at) VALUES (?, ?, ?)")
                .params(route.routeVersionId(), "test-bus", NOW).update();
        }

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles.length()").value(2))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("test-bus"))
            .andExpect(jsonPath("$.vehicles[0].currentStopSequence").value(5))
            .andExpect(jsonPath("$.vehicles[0].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[0].seat.reason").value("QUALITY_WITHHELD"))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[1].seat.remaining").value(12));

        // then
        assertThat(jdbcClient.sql("SELECT remaining_seats FROM vehicle_observation WHERE id=?")
            .param(observation).query(Integer.class).single()).isEqualTo(seats);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_REPORTED", "REPORTED_UNKNOWN"})
    void 원래_좌석이_없던_차량은_품질_조사_중에도_원래_사유를_반환한다(String reason) throws Exception {
        // when
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "예시 정류장", "UP");
        final long batch = insertSuccessfulBatch(route, NOW.minusSeconds(20), "SUCCESS_ROWS", 1);
        insertObservationWithUnknownSeats(batch, route, 0, "test-bus", 5, "205000005", 2, reason);
        jdbcClient.sql("INSERT INTO trip_quality_rebuild(route_version_id, vehicle_id, until_at) VALUES (?, ?, ?)")
            .params(route.routeVersionId(), "test-bus", NOW).update();

        // then
        assertThat(
            jdbcClient.sql("SELECT forecast_eligible FROM forecast_observation_quality WHERE observation_batch_id=?")
                .param(batch)
                .query(Boolean.class)
                .single()).isFalse();

        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].seat").value(aMapWithSize(2)))
            .andExpect(jsonPath("$.vehicles[0].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[0].seat.reason").value(reason))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[0].currentStopSequence").value(5));
    }

    @Test
    void 최신_정상_poll의_차량을_방향과_순번으로_정렬해_계약대로_반환한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 2, "205000002", "하행 두 번째", "DOWN");
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        insertStop(route, 6, "205000006", "상행 여섯 번째", "UP");
        insertStop(route, 9, "205000009", "상행 아홉 번째", "UP");
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        final long batchId = insertSuccessfulBatch(
            route,
            observedAt,
            "SUCCESS_ROWS",
            4
        );
        insertObservation(batchId, route, 0, "204000202", 2, "205000002", 0, 23);
        insertObservation(batchId, route, 1, "204000209", 9, "205000009", 2, 0);
        insertObservationWithUnknownSeats(
            batchId, route, 2, "204000206", 6, "205000006", 2, SEATS_REPORTED_UNKNOWN);
        insertObservationWithUnknownSeats(
            batchId, route, 3, "204000205", 5, "205000005", 1, SEATS_NOT_REPORTED);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=20, public"))
            .andExpect(jsonPath("$").value(aMapWithSize(4)))
            .andExpect(jsonPath("$.routeId").value(ROUTE_ID))
            .andExpect(jsonPath("$.referenceVersionId").value(
                String.valueOf(route.routeVersionId())
            ))
            .andExpect(jsonPath("$.observation").value(aMapWithSize(3)))
            .andExpect(jsonPath("$.observation.state").value("VEHICLES_PRESENT"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(observedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(observedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles.length()").value(4))
            .andExpect(jsonPath("$.vehicles[0]").value(aMapWithSize(7)))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("204000205"))
            .andExpect(jsonPath("$.vehicles[0].direction").value("UP"))
            .andExpect(jsonPath("$.vehicles[0].currentStopSequence").value(5))
            .andExpect(jsonPath("$.vehicles[0].stopId").value("205000005"))
            .andExpect(jsonPath("$.vehicles[0].stationId").doesNotExist())
            .andExpect(jsonPath("$.vehicles[0].stopName").value("상행 다섯 번째"))
            .andExpect(jsonPath("$.vehicles[0].phase").value("ARRIVING"))
            .andExpect(jsonPath("$.vehicles[0].seat").value(aMapWithSize(2)))
            .andExpect(jsonPath("$.vehicles[0].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[0].seat.reason").value("NOT_REPORTED"))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[0].plateNumber").doesNotExist())
            .andExpect(jsonPath("$.vehicles[1].vehicleId").value("204000206"))
            .andExpect(jsonPath("$.vehicles[1].currentStopSequence").value(6))
            .andExpect(jsonPath("$.vehicles[1].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[1].seat.reason").value("REPORTED_UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[1].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[2].vehicleId").value("204000209"))
            .andExpect(jsonPath("$.vehicles[2].currentStopSequence").value(9))
            .andExpect(jsonPath("$.vehicles[2].phase").value("DEPARTED"))
            .andExpect(jsonPath("$.vehicles[2].seat").value(aMapWithSize(2)))
            .andExpect(jsonPath("$.vehicles[2].seat.kind").value("EXACT"))
            .andExpect(jsonPath("$.vehicles[2].seat.reason").doesNotExist())
            .andExpect(jsonPath("$.vehicles[2].seat.remaining").value(0))
            .andExpect(jsonPath("$.vehicles[3].vehicleId").value("204000202"))
            .andExpect(jsonPath("$.vehicles[3].direction").value("DOWN"))
            .andExpect(jsonPath("$.vehicles[3].currentStopSequence").value(2))
            .andExpect(jsonPath("$.vehicles[3].phase").value("IN_TRANSIT"))
            .andExpect(jsonPath("$.vehicles[3].seat.remaining").value(23));
    }

    // 운행_상태를_해석할_수_없는_관측은_그_행만_빼고_나머지를_반환한다 시나리오는 여기서 만들 수 없다.
    // SAL-84가 running_state에 NOT NULL + CHECK (0, 1, 2) 제약을 적용해 그런 행은 DB에 저장할 수 없다.
    // 이 동작은 세 테스트에서 나눠 검증한다.
    //   값 해석          VehiclePhaseTest.문서_밖_운행_상태는_해석하지_않는다
    //   행이 빠지는 것    JpaVehicleQueryRepositoryTest.운행_상태를_해석할_수_없는_관측은_그_행만_빼고_나머지를_돌려준다
    //   애초에 안 쌓는 것  VehicleObservationTableTest.운행_상태는_0과_1과_2만_저장된다

    @Test
    void 상류가_차량_행을_줬는데_읽어낸_관측이_없으면_모르는_것으로_반환한다() throws Exception {
        // given
        // 저장 단계에서 모든 행이 제외된 batch다. outcome은 SUCCESS_ROWS지만 저장된 관측은 없다.
        // 상류가 "차가 없다"고 응답한 적이 없으므로 NO_VEHICLES_OBSERVED가 아니다.
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_ROWS", 0);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void SUCCESS_EMPTY는_운행_종료로_단정하지_않고_빈_배열의_정상_응답을_반환한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_EMPTY", 0);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("NO_VEHICLES_OBSERVED"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(observedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(observedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 최근_NOT_RESERVED_뒤에도_신선한_마지막_성공_차량을_반환한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        OffsetDateTime lastObservedAt = NOW.minusMinutes(2);
        final long successfulBatch = insertSuccessfulBatch(
            route,
            lastObservedAt,
            "SUCCESS_ROWS",
            1
        );
        insertObservation(
            successfulBatch,
            route,
            0,
            "last-success-bus",
            5,
            "205000005",
            1,
            12
        );
        insertNotReservedBatch(route, NOW.minusSeconds(30));

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("VEHICLES_PRESENT"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(lastObservedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(lastObservedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("last-success-bus"));
    }

    @Test
    void 수신_시각_없는_성공은_제외하고_마지막_완료된_성공을_반환한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        OffsetDateTime lastObservedAt = NOW.minusMinutes(2);
        final long completedBatch = insertSuccessfulBatch(
            route,
            lastObservedAt,
            "SUCCESS_ROWS",
            1
        );
        insertObservation(
            completedBatch,
            route,
            0,
            "completed-bus",
            5,
            "205000005",
            1,
            12
        );
        insertIncompleteSuccessfulBatch(route, NOW.minusSeconds(20));

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("VEHICLES_PRESENT"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(lastObservedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(lastObservedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("completed-bus"));
    }

    @Test
    void 예약_순서와_수신_순서가_다르면_나중에_수신한_성공을_선택한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        OffsetDateTime laterReceivedAt = NOW.minusSeconds(20);
        final long laterReceivedBatch = insertSuccessfulBatch(
            route,
            NOW.minusMinutes(2),
            laterReceivedAt,
            "SUCCESS_ROWS",
            1
        );
        insertObservation(
            laterReceivedBatch,
            route,
            0,
            "later-received-bus",
            5,
            "205000005",
            1,
            12
        );
        OffsetDateTime laterScheduledAt = NOW.minusMinutes(1);
        final long laterScheduledBatch = insertSuccessfulBatch(
            route,
            laterScheduledAt,
            NOW.minusSeconds(30),
            "SUCCESS_ROWS",
            1
        );
        insertObservation(
            laterScheduledBatch,
            route,
            0,
            "later-scheduled-bus",
            5,
            "205000005",
            1,
            12
        );

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(laterReceivedAt)))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("later-received-bus"));
    }

    @Test
    void 수신_시각이_같으면_ID가_큰_성공을_선택한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_ROWS", 1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_EMPTY", 0);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("NO_VEHICLES_OBSERVED"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(observedAt)))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 가장_최근_성공이_SUCCESS_EMPTY면_이전_차량을_반환하지_않는다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        final long rowsBatch = insertSuccessfulBatch(
            route,
            NOW.minusMinutes(2),
            "SUCCESS_ROWS",
            1
        );
        insertObservation(
            rowsBatch,
            route,
            0,
            "old-bus",
            5,
            "205000005",
            1,
            12
        );
        OffsetDateTime emptyObservedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(route, emptyObservedAt, "SUCCESS_EMPTY", 0);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("NO_VEHICLES_OBSERVED"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(emptyObservedAt)))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 현재_노선_판본의_성공만_선택한다() throws Exception {
        // given
        RouteContext currentRoute = insertCurrentRoute();
        RouteContext previousRoute = insertPreviousRouteVersion(currentRoute);
        insertSuccessfulBatch(
            previousRoute,
            NOW.minusSeconds(10),
            "SUCCESS_ROWS",
            1
        );
        OffsetDateTime currentObservedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(
            currentRoute,
            currentObservedAt,
            "SUCCESS_EMPTY",
            0
        );

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceVersionId").value(
                String.valueOf(currentRoute.routeVersionId())
            ))
            .andExpect(jsonPath("$.observation.state").value("NO_VEHICLES_OBSERVED"))
            .andExpect(jsonPath("$.observation.observedAt").value(
                jsonTime(currentObservedAt)
            ))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 최신_정상_스냅샷이_자기_staleAt을_넘겼으면_UNKNOWN과_빈_배열을_반환한다() throws Exception {
        // given
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(5).minusSeconds(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_ROWS", 1);

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(observedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(observedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 정상_수집_이력이_없으면_시각이_null인_UNKNOWN을_반환한다() throws Exception {
        // given
        insertCurrentRoute();

        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation").value(aMapWithSize(3)))
            .andExpect(jsonPath("$.observation.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.observation.observedAt").value(nullValue()))
            .andExpect(jsonPath("$.observation.staleAt").value(nullValue()))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 잘못된_노선_ID는_3필드_오류_봉투로_반환한다() throws Exception {
        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$").value(aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").doesNotExist());
    }

    @Test
    void 등록되지_않은_노선은_3필드_오류_봉투로_반환한다() throws Exception {
        // when
        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", "999999999"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$").value(aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").doesNotExist());
    }

    private RouteContext insertCurrentRoute() {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_ID, "GBIS", ROUTE_ID, "3330", "도촌동9단지앞", "안양역")
            .query(Long.class)
            .single();
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, NOW.minusDays(1))
            .query(Long.class)
            .single();

        return new RouteContext(routeId, routeVersionId);
    }

    private RouteContext insertPreviousRouteVersion(RouteContext currentRoute) {
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (
                    route_id, content_digest, valid_from, valid_to
                ) VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                currentRoute.routeId(),
                    "6".repeat(64),
                NOW.minusDays(2),
                NOW.minusDays(1)
            )
            .query(Long.class)
            .single();
        return new RouteContext(currentRoute.routeId(), routeVersionId);
    }

    private String jsonTime(OffsetDateTime value) {
        return JSON_TIME.format(value);
    }

    private void insertStop(
        RouteContext route,
        final int stopOrder,
        String stopId,
        String name,
        String direction
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id,
                    name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(route.routeVersionId(), stopOrder, stopId, name, direction, true)
            .update();
    }

    private long insertSuccessfulBatch(
        RouteContext route,
        OffsetDateTime observedAt,
        String outcome,
        final int storedRows
    ) {
        return insertSuccessfulBatch(
            route,
            observedAt.minusSeconds(2),
            observedAt,
            outcome,
            storedRows
        );
    }

    private long insertSuccessfulBatch(
        RouteContext route,
        OffsetDateTime scheduledAt,
        OffsetDateTime observedAt,
        String outcome,
        final int storedRows
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, completed_at,
                    http_status, result_code, outcome, stored_rows, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                route.routeVersionId(),
                scheduledAt,
                1,
                UUID.randomUUID().toString(),
                observedAt.minusSeconds(1),
                observedAt,
                observedAt.plusNanos(1),
                200,
                0,
                outcome,
                storedRows,
                NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private void insertNotReservedBatch(
        RouteContext route,
        OffsetDateTime scheduledAt
    ) {
        jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    completed_at, outcome, failure_code, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                route.routeVersionId(),
                scheduledAt,
                1,
                UUID.randomUUID().toString(),
                scheduledAt.plusSeconds(2),
                "NOT_RESERVED",
                "LOCAL_QUOTA_EXHAUSTED",
                NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .update();
    }

    private void insertIncompleteSuccessfulBatch(
        RouteContext route,
        OffsetDateTime scheduledAt
    ) {
        jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, completed_at, outcome, stored_rows, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                route.routeVersionId(),
                scheduledAt,
                1,
                UUID.randomUUID().toString(),
                scheduledAt.plusSeconds(1),
                scheduledAt.plusSeconds(2),
                "SUCCESS_ROWS",
                1,
                NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .update();
    }

    private void insertObservation(
        final long batchId,
        RouteContext route,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int runningState,
        final int remainingSeats
    ) {
        insertObservation(
            batchId, route, sourceRowNumber, vehicleId, stopOrder, stopId,
            runningState, remainingSeats, null);
    }

    /**
     * 잔여석을 알 수 없는 관측이다. 상류의 음수와 누락은 정규화 과정에서 사유로 구분하므로
     * DB에는 좌석 수 없이 사유만 남는다. remaining_seats에 -1이 들어가는 일은 없다.
     */
    private void insertObservationWithUnknownSeats(
        final long batchId,
        RouteContext route,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int runningState,
        String seatUnknownReason
    ) {
        insertObservation(
            batchId, route, sourceRowNumber, vehicleId, stopOrder, stopId,
            runningState, null, seatUnknownReason);
    }

    private void insertObservation(
        final long batchId,
        RouteContext route,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int runningState,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                batchId,
                route.routeVersionId(),
                sourceRowNumber,
                vehicleId,
                stopOrder,
                stopId,
                passedStopOrderOf(stopOrder, runningState),
                runningState,
                remainingSeats,
                seatUnknownReason
            )
            .update();
    }

    /** 도착 중(1)인 버스는 그 정류소를 아직 안 지났다. */
    private int passedStopOrderOf(
        final int stopOrder,
        final int runningState
    ) {
        if (runningState == RUNNING_STATE_ARRIVING) {
            return stopOrder - 1;
        }
        return stopOrder;
    }

    private record RouteContext(long routeId, long routeVersionId) {
    }

    private static String tripState(String reason) {
        if (reason.equals("excluded_trip")) {
            return "EXCLUDED";
        }
        return "BOUNDARY_UNCONFIRMED";
    }
}
