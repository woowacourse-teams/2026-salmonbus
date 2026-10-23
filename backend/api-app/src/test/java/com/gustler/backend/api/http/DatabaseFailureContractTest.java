package com.gustler.backend.api.http;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.DedicatedPostgresTestContainer;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실행 중 DB 서버를 잃으면 보드와 노선 목록이 공통 503 오류로 응답한다.
 *
 * <p>서버를 멈추므로 공유 서버가 아니라 이 컨텍스트만 쓰는 서버({@link DedicatedPostgresTestContainer})에서 실행한다.
 * 서버를 한 번만 띄우고 멈추도록 두 경로를 한 시험에서 확인한다.
 */
@SpringBootTest
@Import(DedicatedPostgresTestContainer.class)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.datasource.hikari.connection-timeout=250",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.minimum-idle=0"
})
class DatabaseFailureContractTest {
    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private PostgreSQLContainer postgres;

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
    void 실행_중_DB_연결을_잃으면_보드와_노선_목록이_공통_503_오류로_응답한다() throws Exception {
        // given
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
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long batch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, now, now);

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk());

        postgres.stop();

        // when
        ResultActions board = mockMvc.perform(get("/api/v1/routes/204000057/board"));
        ResultActions routes = mockMvc.perform(get("/api/v1/routes"));

        // then
        board
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("일시적인 서버 장애가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        routes
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$").value(aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("일시적인 서버 장애가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").doesNotExist());
    }
}
