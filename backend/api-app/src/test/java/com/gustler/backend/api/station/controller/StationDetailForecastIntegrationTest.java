package com.gustler.backend.api.station.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.ForecastBoardFixture;
import com.gustler.backend.api.support.FixedDaytimeClockConfig;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 정류장 상세가 예보 기록에서 읽은 예측을 우리 노선 차량 행에 그대로 싣는지 실제 DB와 실제 직렬화로 검증한다.
 */
@IntegrationTest
@Transactional
@Import(FixedDaytimeClockConfig.class)
class StationDetailForecastIntegrationTest {

    private static final String VEHICLE = "$.routes[0].arrivals[0]";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private ForecastBoardFixture fixture;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        fixture = new ForecastBoardFixture(jdbcClient, objectMapper);
        now = OffsetDateTime.now(clock).withNano(0);
    }

    @Test
    void 예측이_있는_우리_노선_차량은_넣은_확률과_기대_좌석을_그대로_싣는다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertForecast(
                vehicle,
                modelId,
                0.25,
                12.5
        );

        // when
        ResultActions actual = getStationDetail();

        // then
        actual.andExpect(status().isOk())
            .andExpect(jsonPath("$.routes.length()").value(1))
            .andExpect(jsonPath("$.routes[0].routeId").value(ForecastBoardFixture.ROUTE_ID))
            .andExpect(jsonPath("$.routes[0].supported").value(true))
            .andExpect(jsonPath("$.routes[0].service.routeStatus").value("FORECAST_READY"))
            .andExpect(jsonPath("$.routes[0].arrivals.length()").value(1))
            .andExpect(jsonPath(VEHICLE + ".vehicleId").value(ForecastBoardFixture.VEHICLE_ID))
            .andExpect(jsonPath(VEHICLE + ".positionSource").value("BOARD"))
            .andExpect(jsonPath(VEHICLE + ".remainingStops").value(1))
            .andExpect(jsonPath(VEHICLE + ".forecast.status").value("AVAILABLE"))
            .andExpect(jsonPath(VEHICLE + ".forecast.seatAvailableProbability").value(0.75))
            .andExpect(jsonPath(VEHICLE + ".forecast.expectedSeats").value(12.5));
    }

    @Test
    void 판정보드에_있는_차량에_예측이_없으면_예보는_UNAVAILABLE이다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertRunWithoutForecast(vehicle, modelId, "OUT_OF_SCOPE");

        // when
        ResultActions actual = getStationDetail();

        // then
        actual.andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].service.routeStatus").value("FORECAST_READY"))
            .andExpect(jsonPath("$.routes[0].arrivals.length()").value(1))
            .andExpect(jsonPath(VEHICLE + ".vehicleId").value(ForecastBoardFixture.VEHICLE_ID))
            .andExpect(jsonPath(VEHICLE + ".positionSource").value("BOARD"))
            .andExpect(jsonPath(VEHICLE + ".forecast.status").value("UNAVAILABLE"))
            .andExpect(jsonPath(VEHICLE + ".forecast.seatAvailableProbability").value(nullValue()))
            .andExpect(jsonPath(VEHICLE + ".forecast.expectedSeats").value(nullValue()));
    }

    @Test
    void 판정보드를_못_읽으면_도착정보에만_있는_차량의_예보는_BOARD_UNAVAILABLE이다() throws Exception {
        // given
        fixture.insertRoute(now.minusDays(1));
        fixture.insertActiveModel(now.minusDays(1));
        fixture.insertArrival(now.minusSeconds(10).toInstant(), 300);

        // when
        ResultActions actual = getStationDetail();

        // then
        actual.andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].routeId").value(ForecastBoardFixture.ROUTE_ID))
            .andExpect(jsonPath("$.routes[0].service.routeStatus").value("PREPARING"))
            .andExpect(jsonPath("$.routes[0].arrivals.length()").value(1))
            .andExpect(jsonPath(VEHICLE + ".vehicleId").value(ForecastBoardFixture.VEHICLE_ID))
            .andExpect(jsonPath(VEHICLE + ".positionSource").value("ARRIVAL"))
            .andExpect(jsonPath(VEHICLE + ".forecast.status").value("BOARD_UNAVAILABLE"))
            .andExpect(jsonPath(VEHICLE + ".forecast.seatAvailableProbability").value(nullValue()))
            .andExpect(jsonPath(VEHICLE + ".forecast.expectedSeats").value(nullValue()));
    }

    private ResultActions getStationDetail() throws Exception {
        return mockMvc.perform(get("/api/v2/stations/{stationId}", ForecastBoardFixture.STOP_ID));
    }
}
