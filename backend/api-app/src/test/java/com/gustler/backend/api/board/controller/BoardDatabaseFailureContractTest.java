package com.gustler.backend.api.board.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

@IntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.datasource.hikari.connection-timeout=250",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.minimum-idle=0"
})
class BoardDatabaseFailureContractTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private PostgreSQLContainer<?> postgres;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private BoardDatabaseFixture fixture;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(applicationContext)
            .build();
        fixture = new BoardDatabaseFixture(jdbcClient);
    }

    @Test
    void 실행_중_DB_연결을_잃으면_공통_503_오류로_응답한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        RouteContext route = fixture.insertRoute(
            "204000057",
            "3330",
            "기점",
            "종점",
            2,
            "04:50",
            "23:30",
            "05:00",
            "23:30",
            now.minusDays(1)
        );
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", true);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
        fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        fixture.insertBatch(route, now.minusMinutes(1), now, "SUCCESS_EMPTY", 0);

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk());

        postgres.stop();

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("일시적인 서버 장애가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
