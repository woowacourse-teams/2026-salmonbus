package com.gustler.backend.api.observation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.observation.NightObservationApiContractTest.FixedClockConfig;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
@Import(FixedClockConfig.class)
class NightObservationApiContractTest {
    private static final String ROUTE = "204000057";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T19:05:00Z");

    @Autowired
    private WebApplicationContext application;
    @Autowired
    private JdbcClient jdbc;
    private MockMvc api;
    private BoardDatabaseFixture fixture;

    @BeforeEach
    void prepare() {
        api = MockMvcBuilders.webAppContextSetup(application).build();
        fixture = new BoardDatabaseFixture(jdbc);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-15T18:55:00Z, 10, true",
        "2026-10-15T18:54:59.999999Z, 10, false",
        "2026-10-15T18:59:00Z, 10, true",
        "2026-10-15T18:59:59.999999Z, 10, true",
        "2026-10-15T19:00:00Z, 5, true"
    })
    void 탑승판과_차량은_같은_관측_기준_만료_시각을_쓴다(String time, final int minutes,
        final boolean fresh) throws Exception {
        // given
        OffsetDateTime observedAt = OffsetDateTime.parse(time);
        RouteContext route = fixture.insertRoute(ROUTE, "3330", "기점", "종점", 2,
            "04:50", "23:30", "05:00", "23:30", NOW.minusDays(1));
        fixture.insertStop(route, 1, "205000001", "기점", "UP", true);
        fixture.insertStop(route, 2, "205000002", "회차점", "UP", false);
        fixture.insertStop(route, 3, "205000003", "종점", "DOWN", true);
        fixture.insertModel("night-model", "ACTIVE", NOW.minusDays(1));
        final long batch = fixture.insertBatch(route, observedAt, "SUCCESS_ROWS", 1);
        fixture.insertObservation(route, batch, 0, "night-test", 1, "205000001", observedAt);
        String expectedExpiry = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(observedAt.plusMinutes(minutes));
        String expectedVehicleExpiry = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            observedAt.plusMinutes(minutes).atZoneSameInstant(ZoneId.of("Asia/Seoul")));

        // when
        ResultActions boardResponse = api.perform(get("/api/v1/routes/{routeId}/board", ROUTE));

        // then
        assertBoardFreshness(boardResponse, fresh, expectedExpiry);

        // when
        ResultActions watchResponse = api.perform(get("/api/v1/routes/{routeId}/watch", ROUTE));

        // then
        assertWatchFreshness(watchResponse, fresh, expectedExpiry);
        String expectedState = "UNKNOWN";
        int expectedVehicleCount = 0;
        if (fresh) {
            expectedState = "VEHICLES_PRESENT";
            expectedVehicleCount = 1;
        }

        // when
        ResultActions vehicleResponse = api.perform(get("/api/v1/routes/{routeId}/vehicles", ROUTE));

        // then
        vehicleResponse.andExpect(status().isOk())
            .andExpect(jsonPath("$.observation.state").value(expectedState))
            .andExpect(jsonPath("$.observation.staleAt").value(expectedVehicleExpiry))
            .andExpect(jsonPath("$.vehicles.length()").value(expectedVehicleCount));
    }

    private static void assertBoardFreshness(
        ResultActions response,
        final boolean fresh,
        String expectedExpiry
    ) throws Exception {
        if (fresh) {
            response.andExpect(status().isOk())
                .andExpect(jsonPath("$.route.status").value("PREPARING"))
                .andExpect(jsonPath("$.staleAt").value(expectedExpiry));
            return;
        }
        response.andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"));
    }

    private static void assertWatchFreshness(
        ResultActions response,
        final boolean fresh,
        String expectedExpiry
    ) throws Exception {
        if (fresh) {
            response.andExpect(status().isOk())
                .andExpect(jsonPath("$.staleAt").value(expectedExpiry))
                .andExpect(jsonPath("$.vehicles.length()").value(1));
            return;
        }
        response.andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedNightClock() {
            return Clock.fixed(Instant.parse("2026-10-15T19:05:00Z"), ZoneOffset.UTC);
        }
    }
}
