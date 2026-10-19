package com.gustler.backend.api.board.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
@Import(WatchBoardNightCacheApiContractTest.FixedClockConfig.class)
class WatchBoardNightCacheApiContractTest {

    private static final String ROUTE_ID = "204000057";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T19:05:00Z");

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mockMvc;
    private BoardDatabaseFixture fixture;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        fixture = new BoardDatabaseFixture(jdbcClient);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-15T18:55:00Z, 0",
        "2026-10-15T18:59:00Z, 240",
        "2026-10-15T18:59:59.999999Z, 299",
        "2026-10-15T19:00:00Z, 0"
    })
    void 심야_관측은_정책_캐시_시간과_만료까지_남은_시간_중_짧은_쪽만큼_캐시한다(String time, final int maxAge)
        throws Exception {
        // given
        OffsetDateTime observedAt = OffsetDateTime.parse(time);
        RouteContext route = fixture.insertRoute(ROUTE_ID, "3330", "기점", "종점", 2,
            "04:50", "23:30", "05:00", "23:30", NOW.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", false);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
        final long model = fixture.insertModel("night-model", "ACTIVE", NOW.minusDays(1));
        final long batch = fixture.insertBatch(route, observedAt, "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, observedAt, observedAt);

        // when & then
        mockMvc.perform(get("/api/v1/routes/{routeId}/watch", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=" + maxAge + ", public"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedSeoulNightClock() {
            return Clock.fixed(Instant.parse("2026-10-15T19:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
