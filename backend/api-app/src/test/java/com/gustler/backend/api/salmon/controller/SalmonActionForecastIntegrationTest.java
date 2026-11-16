package com.gustler.backend.api.salmon.controller;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 연어행동 조회를 켠 앱이 예보 기록에서 읽은 출발 만차 확률로 판정까지 가는지 실제 DB로 검증한다.
 *
 * <p>차량 한 대가 마감 안에 닿는 도착정보를 함께 넣는다. 도보 거리는 넣지 않아 이동 후보는 없다.
 */
@IntegrationTest
@Transactional
@Import(FixedDaytimeClockConfig.class)
@TestPropertySource(properties = "salmon.action.enabled=true")
class SalmonActionForecastIntegrationTest {
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
    void 마감_안에_닿는_차량의_만차_확률이_역치보다_낮으면_옮기지_않아도_된다고_판정한다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertForecast(vehicle, modelId, 0.25, 12.5);
        fixture.insertArrival(now.minusSeconds(10).toInstant(), 300);

        // when
        ResultActions actual = getSalmonAction();

        // then
        actual.andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DECIDED"))
            .andExpect(jsonPath("$.reasonCode").value("NO_MOVE_POLICY"))
            .andExpect(jsonPath("$.guidance").value("지금은 정류장을 옮기지 않아도 돼요."));
    }

    @Test
    void 마감_안에_닿는_차량에_예측이_없으면_판정을_보류하고_문장을_싣지_않는다() throws Exception {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertRunWithoutForecast(vehicle, modelId, "OUT_OF_SCOPE");
        fixture.insertArrival(now.minusSeconds(10).toInstant(), 300);

        // when
        ResultActions actual = getSalmonAction();

        // then
        actual.andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DECIDED"))
            .andExpect(jsonPath("$.reasonCode").value("ABSTAIN_EVIDENCE"))
            .andExpect(jsonPath("$.guidance").doesNotExist());
    }

    private ResultActions getSalmonAction() throws Exception {
        return mockMvc.perform(get(
            "/api/v1/routes/{routeId}/stations/{stationId}/salmon-action",
            ForecastBoardFixture.ROUTE_ID,
            ForecastBoardFixture.STOP_ID)
            .param("stopSequence", String.valueOf(ForecastBoardFixture.STOP_SEQUENCE)));
    }
}
