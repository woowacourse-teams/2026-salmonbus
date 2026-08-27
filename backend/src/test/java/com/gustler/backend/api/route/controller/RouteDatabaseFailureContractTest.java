package com.gustler.backend.api.route.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
class RouteDatabaseFailureContractTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private PostgreSQLContainer<?> postgres;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(applicationContext)
            .build();
    }

    @Test
    void 실행_중_DB_연결을_잃으면_공통_503_오류로_응답한다() throws Exception {
        // given
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk());
        postgres.stop();

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
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
