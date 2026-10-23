package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.support.FixedDaytimeClockConfig;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
@Transactional
@Import(FixedDaytimeClockConfig.class)
class WatchBoardApiContractTest {
    private static final String ROUTE_ID = "204000057";
    private static final int IN_TRANSIT = 0;
    private static final int ARRIVING = 1;
    private static final int DEPARTED = 2;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private BoardDatabaseFixture fixture;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        fixture = new BoardDatabaseFixture(jdbcClient);
        now = OffsetDateTime.now(clock).withNano(0);
    }

    @Test
    void 예보와_차량_위치를_더_최신의_미발행_관측이_아니라_예보가_발행된_같은_관측에서_준다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime publishedAt = now.minusMinutes(1);
        final long published = fixture.insertBatch(route, publishedAt, "SUCCESS_ROWS", 1);
        final long observation = fixture.insertObservation(route, published, 1, "V1", 1, "STOP-1",
            1, DEPARTED, 7, null);
        fixture.insertPublication(published, model, publishedAt.plusSeconds(1), publishedAt.plusSeconds(2));
        fixture.insertForecast(route, observation, 3, 2, model, 0.3, 4.0, publishedAt.plusSeconds(1));
        final long newer = fixture.insertBatch(route, now.minusSeconds(30), "SUCCESS_ROWS", 1);
        fixture.insertObservation(route, newer, 1, "V1", 2, "STOP-2", 1, ARRIVING, 3, null);

        // when
        ResultActions httpResponse = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", aMapWithSize(7)))
            .andExpect(jsonPath("$.observedAt").value(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(publishedAt)))
            .andExpect(jsonPath("$.route.status").value("FORECAST_READY"))
            .andExpect(jsonPath("$.vehiclesInService").value(1))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].vehicleId").value("V1"))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].horizonStops").value(2))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("V1"))
            .andExpect(jsonPath("$.vehicles[0].currentStopSequence").value(1))
            .andExpect(jsonPath("$.vehicles[0].phase").value("DEPARTED"))
            .andExpect(jsonPath("$.vehicles[0].seat.kind").value("EXACT"))
            .andExpect(jsonPath("$.vehicles[0].seat.remaining").value(7));
    }

    @Test
    void 차량_목록을_뺀_응답과_헤더는_보드와_같다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime publishedAt = now.minusMinutes(1);
        final long published = fixture.insertBatch(route, publishedAt, "SUCCESS_ROWS", 2);
        final long first = fixture.insertObservation(route, published, 1, "V1", 1, "STOP-1", 1, DEPARTED, 7, null);
        fixture.insertObservation(route, published, 2, "V2", 3, "STOP-3", 2, ARRIVING, null, "REPORTED_UNKNOWN");
        fixture.insertPublication(published, model, publishedAt.plusSeconds(1), publishedAt.plusSeconds(2));
        fixture.insertForecast(route, first, 3, 2, model, 0.3, 4.0, publishedAt.plusSeconds(1));

        // when
        MvcResult watch = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID)).andReturn();
        MvcResult board = mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID)).andReturn();

        // then
        ObjectNode watchBody = (ObjectNode) bodyOf(watch);
        watchBody.remove("vehicles");
        assertThat(watch.getResponse().getStatus()).isEqualTo(200);
        assertThat(watchBody).isEqualTo(bodyOf(board));
        assertThat(watch.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
            .isEqualTo(board.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void 노선_버전에_저장된_요일별_배차간격을_노선_정보에_준다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime publishedAt = now.minusMinutes(1);
        final long published = fixture.insertBatch(route, publishedAt, "SUCCESS_ROWS", 1);
        fixture.insertObservation(route, published, 1, "V1", 1, "STOP-1", 1, DEPARTED, 7, null);
        fixture.insertPublication(published, model, publishedAt.plusSeconds(1), publishedAt.plusSeconds(2));
        fixture.updateHeadway(route, "weekday", 10, 20);
        fixture.updateHeadway(route, "holiday", 16, 35);

        // when
        ResultActions httpResponse2 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse2
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.route.headways.weekday.minMinutes").value(10))
            .andExpect(jsonPath("$.route.headways.weekday.maxMinutes").value(20))
            .andExpect(jsonPath("$.route.headways.saturday").isEmpty())
            .andExpect(jsonPath("$.route.headways.sunday").isEmpty())
            .andExpect(jsonPath("$.route.headways.holiday.minMinutes").value(16))
            .andExpect(jsonPath("$.route.headways.holiday.maxMinutes").value(35));
    }

    @Test
    void 최신_관측이_발행되어_있으면_차량_목록은_차량_API와_같다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime publishedAt = now.minusSeconds(30);
        final long published = fixture.insertBatch(route, publishedAt, "SUCCESS_ROWS", 3);
        fixture.insertObservation(route, published, 1, "V1", 1, "STOP-1", 1, DEPARTED, 0, null);
        fixture.insertObservation(route, published, 2, "V2", 2, "STOP-2", 1, ARRIVING, null, "NOT_REPORTED");
        fixture.insertObservation(route, published, 3, "V3", 3, "STOP-3", 3, IN_TRANSIT, 12, null);
        fixture.insertPublication(published, model, publishedAt.plusSeconds(1), publishedAt.plusSeconds(2));

        // when
        JsonNode watch = bodyOf(mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID)).andReturn());
        JsonNode vehicles = bodyOf(mockMvc.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE_ID)).andReturn());

        // then
        assertThat(watch.path("vehicles")).isEqualTo(vehicles.path("vehicles"));
        assertThat(watch.path("vehicles").size()).isEqualTo(watch.path("vehiclesInService").asInt());
        assertThat(watch.path("vehicles").get(0).path("seat").path("remaining").asInt()).isZero();
        assertThat(watch.path("vehicles").get(1).path("seat").path("reason").asText()).isEqualTo("NOT_REPORTED");
    }

    @Test
    void 발행된_예보가_없으면_최신_정상_관측의_차량과_함께_PREPARING_보드를_준다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long batch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_ROWS", 1);
        fixture.insertObservation(route, batch, 1, "V1", 1, "STOP-1", 1, DEPARTED, 5, null);

        // when
        ResultActions httpResponse3 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse3
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.route.status").value("PREPARING"))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].forecast.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("V1"));
    }

    @Test
    void 이전_판본에만_더_최신의_발행이_있으면_현재_판본의_관측과_차량만_준다() throws Exception {
        // given
        RouteContext oldRoute = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        RouteContext currentRoute = fixture.insertNextVersion(oldRoute, now.minusMinutes(10));
        insertRoundTripStopsOf(currentRoute);
        final long current = fixture.insertBatch(currentRoute, now.minusMinutes(1), "SUCCESS_ROWS", 1);
        fixture.insertObservation(currentRoute, current, 1, "NEW", 1, "STOP-1", 1, DEPARTED, 5, null);
        final long old = fixture.insertBatch(oldRoute, now.minusSeconds(20), "SUCCESS_ROWS", 1);
        fixture.insertObservation(oldRoute, old, 1, "OLD", 1, "STOP-1", 1, DEPARTED, 5, null);
        fixture.insertPublication(old, model, now.minusSeconds(19), now.minusSeconds(18));

        // when
        ResultActions httpResponse4 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse4
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.route.status").value("PREPARING"))
            .andExpect(jsonPath("$.route.referenceVersionId").value(String.valueOf(currentRoute.routeVersionId())))
            .andExpect(jsonPath("$.vehicles.length()").value(1))
            .andExpect(jsonPath("$.vehicles[0].vehicleId").value("NEW"));
    }

    @Test
    void 차량이_없는_발행이면_차량_0대와_빈_목록을_준다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long empty = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(empty, model, now.minusSeconds(59), now.minusSeconds(58));

        // when
        ResultActions httpResponse5 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse5
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehiclesInService").value(0))
            .andExpect(jsonPath("$.vehicles").isEmpty())
            .andExpect(jsonPath("$.stops[2].approachingVehicles").isEmpty());
    }

    @Test
    void 발행된_예보가_5분을_넘으면_더_최신의_미발행_관측이_있어도_NO_RECENT_OBSERVATION_503이다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long stale = fixture.insertBatch(route, now.minusMinutes(5).minusSeconds(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(stale, model, now.minusMinutes(5), now.minusMinutes(5));
        fixture.insertBatch(route, now.minusSeconds(30), "SUCCESS_ROWS", 1);

        // when
        ResultActions httpResponse6 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse6
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"));
    }

    @Test
    void ACTIVE_모델이_없으면_MODEL_OUT_OF_SCOPE_503이다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);

        // when
        ResultActions httpResponse7 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse7
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("MODEL_OUT_OF_SCOPE"));
    }

    @Test
    void 노선_ID가_잘못되면_400_없는_노선이면_404_POST면_405다() throws Exception {
        // when
        ResultActions httpResponse8 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", "abc"));

        // then
        httpResponse8
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"));

        // when
        ResultActions httpResponse9 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", "999999999"));

        // then
        httpResponse9
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));

        // when
        ResultActions httpResponse10 = mockMvc.perform(post("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse10
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void HEAD_요청도_GET과_같은_캐시_헤더로_응답한다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long empty = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(empty, model, now.minusSeconds(59), now.minusSeconds(58));

        // when
        ResultActions httpResponse11 = mockMvc.perform(head("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse11
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=20, public"));
    }

    @Test
    void 만료가_정책_캐시_시간보다_먼저_오면_만료까지만_캐시한다() throws Exception {
        // given
        RouteContext route = insertRoundTripRoute();
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime observedAt = now.minusMinutes(4).minusSeconds(50);
        final long batch = fixture.insertBatch(route, observedAt, "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, observedAt.plusSeconds(1), observedAt.plusSeconds(2));

        // when
        ResultActions httpResponse12 = mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID));

        // then
        httpResponse12
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=10, public"));
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private RouteContext insertRoundTripRoute() {
        RouteContext route = fixture.insertRoute(ROUTE_ID, "3330", "기점", "종점", 2,
            "04:50", "23:30", "05:00", "23:30", now.minusDays(1));
        insertRoundTripStopsOf(route);
        return route;
    }

    private void insertRoundTripStopsOf(RouteContext route) {
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", false);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
    }
}
