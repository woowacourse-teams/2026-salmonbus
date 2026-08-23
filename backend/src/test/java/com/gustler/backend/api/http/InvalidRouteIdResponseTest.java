package com.gustler.backend.api.http;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.route.RouteId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({ApiExceptionHandler.class, InvalidRouteIdResponseTest.TestController.class})
class InvalidRouteIdResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 아홉_자리_노선_ID를_컨트롤러에_전달한다() throws Exception {
        mockMvc.perform(get("/test/routes/204000057"))
            .andExpect(status().isOk())
            .andExpect(content().string("204000057"));
    }

    @Test
    void 잘못된_노선_ID에_400과_오류_응답을_반환한다() throws Exception {
        mockMvc.perform(get("/test/routes/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(4)))
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"))
            .andExpect(jsonPath("$.message").value("routeId는 9자리 숫자여야 합니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/routes/{routeId}")
        String route(
            @PathVariable final RouteId routeId
        ) {
            return routeId.value();
        }
    }
}
