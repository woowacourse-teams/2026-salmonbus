package com.gustler.backend.api.http.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@ActiveProfiles({"api-docs-dev", "prod"})
class ProductionApiDocsDisabledTest {

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void 운영_프로필이_겹쳐도_화면과_명세를_제공하지_않는다() throws Exception {
        mockMvc.perform(get("/api/docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/ui")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/openapi")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/openapi.yaml")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/openapi/swagger-config")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/chat")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }
}
