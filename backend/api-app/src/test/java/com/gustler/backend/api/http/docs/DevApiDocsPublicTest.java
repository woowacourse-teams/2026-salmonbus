package com.gustler.backend.api.http.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@ActiveProfiles("api-docs-dev")
class DevApiDocsPublicTest {

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void dev_프로필에서는_익명_문서_조회만_켜고_시험_요청은_끄고_둔다() throws Exception {
        mockMvc.perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v2/stations/{stationId}/arrivals'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v2/routes/{routeId}/geometry'].get").exists());
        mockMvc.perform(get("/api/docs/openapi/swagger-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.supportedSubmitMethods").isEmpty());
    }
}
