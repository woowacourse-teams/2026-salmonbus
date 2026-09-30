package com.gustler.backend.api.chat.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.chat.application.ChatTestRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunctions;

class ChatAvailabilityControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatAvailabilityHandler handler = new ChatAvailabilityHandler(ChatTestRoutes.catalog());
        mockMvc = MockMvcBuilders.routerFunctions(
            RouterFunctions.route()
                .GET("/api/chat/rooms/{routeId}", handler::availability)
                .build()
        ).build();
    }

    @Test
    void 지원_노선의_채팅_계약을_MongoDB_없이_반환한다() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/204000057"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(content().json(
                ChatContractExamples.compact(ChatContractExamples.availability()),
                JsonCompareMode.STRICT
            ));
    }

    @Test
    void 지원하지_않는_노선은_404와_no_store를_반환한다() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/unknown"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
}
