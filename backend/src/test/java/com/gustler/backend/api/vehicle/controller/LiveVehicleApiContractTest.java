package com.gustler.backend.api.vehicle.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
@Import(LiveVehicleApiContractTest.FixedClockConfig.class)
class LiveVehicleApiContractTest {

    private static final String ROUTE_ID = "204000057";
    private static final OffsetDateTime NOW = OffsetDateTime.parse(
        "2026-08-27T12:00:00+09:00"
    );
    private static final String CONTENT_DIGEST = "5".repeat(64);
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

    @Test
    void 최신_정상_poll의_차량을_방향과_순번으로_정렬해_계약대로_반환한다() throws Exception {
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
        insertObservation(batchId, route, 2, "204000206", 6, "205000006", 2, -1);
        insertObservationWithoutSeats(
            batchId,
            route,
            3,
            "204000205",
            5,
            "205000005",
            1
        );

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
            .andExpect(jsonPath("$.vehicles[0].seat").value(aMapWithSize(1)))
            .andExpect(jsonPath("$.vehicles[0].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[0].plateNumber").doesNotExist())
            .andExpect(jsonPath("$.vehicles[1].vehicleId").value("204000206"))
            .andExpect(jsonPath("$.vehicles[1].currentStopSequence").value(6))
            .andExpect(jsonPath("$.vehicles[1].seat.kind").value("UNKNOWN"))
            .andExpect(jsonPath("$.vehicles[1].seat.remaining").doesNotExist())
            .andExpect(jsonPath("$.vehicles[2].vehicleId").value("204000209"))
            .andExpect(jsonPath("$.vehicles[2].currentStopSequence").value(9))
            .andExpect(jsonPath("$.vehicles[2].phase").value("DEPARTED"))
            .andExpect(jsonPath("$.vehicles[2].seat").value(aMapWithSize(2)))
            .andExpect(jsonPath("$.vehicles[2].seat.kind").value("EXACT"))
            .andExpect(jsonPath("$.vehicles[2].seat.remaining").value(0))
            .andExpect(jsonPath("$.vehicles[3].vehicleId").value("204000202"))
            .andExpect(jsonPath("$.vehicles[3].direction").value("DOWN"))
            .andExpect(jsonPath("$.vehicles[3].currentStopSequence").value(2))
            .andExpect(jsonPath("$.vehicles[3].phase").value("IN_TRANSIT"))
            .andExpect(jsonPath("$.vehicles[3].seat.remaining").value(23));
    }

    @Test
    void 운행_상태를_해석할_수_없는_관측은_그_행만_빼고_나머지를_반환한다() throws Exception {
        RouteContext route = insertCurrentRoute();
        insertStop(route, 3, "205000003", "상행 세 번째", "UP");
        insertStop(route, 5, "205000005", "상행 다섯 번째", "UP");
        insertStop(route, 7, "205000007", "상행 일곱 번째", "UP");
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        final long batchId = insertSuccessfulBatch(
            route,
            observedAt,
            "SUCCESS_ROWS",
            3
        );
        insertObservationWithoutRunningState(
            batchId,
            route,
            0,
            "204000203",
            3,
            "205000003",
            12
        );
        insertObservation(batchId, route, 1, "204000205", 5, "205000005", 1, 7);
        insertObservation(batchId, route, 2, "204000207", 7, "205000007", 9, 3);

        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("VEHICLES_PRESENT"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(observedAt)))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("204000205"))
            .andExpect(jsonPath("$.vehicles[0].currentStopSequence").value(5))
            .andExpect(jsonPath("$.vehicles[0].phase").value("ARRIVING"))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").value(7));
    }

    @Test
    void SUCCESS_EMPTY는_운행_종료로_단정하지_않고_빈_배열의_정상_응답을_반환한다() throws Exception {
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_EMPTY", 0);

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
    void 최신_poll이_실패하면_마지막_정상_시각과_UNKNOWN을_반환한다() throws Exception {
        RouteContext route = insertCurrentRoute();
        OffsetDateTime lastObservedAt = NOW.minusMinutes(2);
        insertSuccessfulBatch(route, lastObservedAt, "SUCCESS_ROWS", 1);
        insertFailedBatch(route, NOW.minusSeconds(30));

        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(lastObservedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(lastObservedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 최신_성공_poll에_응답_수신_시각이_없으면_불완전한_관측으로_처리한다() throws Exception {
        RouteContext route = insertCurrentRoute();
        OffsetDateTime lastObservedAt = NOW.minusMinutes(2);
        insertSuccessfulBatch(route, lastObservedAt, "SUCCESS_ROWS", 1);
        insertIncompleteSuccessfulBatch(route, NOW.minusSeconds(20));

        mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.observation.observedAt").value(jsonTime(lastObservedAt)))
            .andExpect(jsonPath("$.observation.staleAt").value(
                jsonTime(lastObservedAt.plusMinutes(5))
            ))
            .andExpect(jsonPath("$.vehicles").isEmpty());
    }

    @Test
    void 최신_정상_스냅샷이_자기_staleAt을_넘겼으면_UNKNOWN과_빈_배열을_반환한다() throws Exception {
        RouteContext route = insertCurrentRoute();
        OffsetDateTime observedAt = NOW.minusMinutes(5).minusSeconds(1);
        insertSuccessfulBatch(route, observedAt, "SUCCESS_ROWS", 1);

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
        insertCurrentRoute();

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

        return new RouteContext(routeVersionId);
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
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, completed_at,
                    http_status, result_code, outcome, stored_rows
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                route.routeVersionId(),
                observedAt.minusSeconds(2),
                1,
                UUID.randomUUID().toString(),
                observedAt.minusSeconds(1),
                observedAt,
                observedAt.plusNanos(1),
                200,
                0,
                outcome,
                storedRows
            )
            .query(Long.class)
            .single();
    }

    private void insertFailedBatch(
        RouteContext route,
        OffsetDateTime scheduledAt
    ) {
        jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, completed_at, outcome, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                route.routeVersionId(),
                scheduledAt,
                1,
                UUID.randomUUID().toString(),
                scheduledAt.plusSeconds(1),
                scheduledAt.plusSeconds(2),
                "TRANSPORT_FAILURE",
                "UPSTREAM_TIMEOUT"
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
                    requested_at, completed_at, outcome, stored_rows
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                route.routeVersionId(),
                scheduledAt,
                1,
                UUID.randomUUID().toString(),
                scheduledAt.plusSeconds(1),
                scheduledAt.plusSeconds(2),
                "SUCCESS_ROWS",
                1
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
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    observed_at, vehicle_id, stop_order, stop_id,
                    running_state, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                batchId,
                route.routeVersionId(),
                sourceRowNumber,
                NOW.minusMinutes(1),
                vehicleId,
                stopOrder,
                stopId,
                runningState,
                remainingSeats
            )
            .update();
    }

    private void insertObservationWithoutRunningState(
        final long batchId,
        RouteContext route,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int remainingSeats
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    observed_at, vehicle_id, stop_order, stop_id, remaining_seats
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                batchId,
                route.routeVersionId(),
                sourceRowNumber,
                NOW.minusMinutes(1),
                vehicleId,
                stopOrder,
                stopId,
                remainingSeats
            )
            .update();
    }

    private void insertObservationWithoutSeats(
        final long batchId,
        RouteContext route,
        final int sourceRowNumber,
        String vehicleId,
        final int stopOrder,
        String stopId,
        final int runningState
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    observed_at, vehicle_id, stop_order, stop_id, running_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                batchId,
                route.routeVersionId(),
                sourceRowNumber,
                NOW.minusMinutes(1),
                vehicleId,
                stopOrder,
                stopId,
                runningState
            )
            .update();
    }

    private record RouteContext(long routeVersionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedVehicleClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }
}
