package com.gustler.backend.api.http;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({ApiExceptionHandler.class, RouteNotFoundResponseTest.TestController.class})
class RouteNotFoundResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 등록되지_않은_노선_ID에_404와_오류_응답을_반환한다() throws Exception {
        mockMvc.perform(get("/test/routes/999999999"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(4)))
            .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("등록되지 않은 노선입니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/routes/{routeId}")
        void route(
            @PathVariable final RouteId routeId
        ) {
            throw new RouteNotFoundException();
        }
    }
}
