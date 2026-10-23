package com.gustler.backend.api.http.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
class DisabledApiDocsContractTest {
    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void 기본_설정은_화면_명세_별도_계약을_제공하지_않는다() throws Exception {
        // when
        mockMvc.perform(get("/api/docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/ui")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/openapi")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/openapi.yaml")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/chat")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/docs/chat/examples")).andExpect(status().isNotFound());
    }
}
