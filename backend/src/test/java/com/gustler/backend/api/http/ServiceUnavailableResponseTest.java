package com.gustler.backend.api.http;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({ApiExceptionHandler.class, ServiceUnavailableResponseTest.TestController.class})
class ServiceUnavailableResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 일시적인_서버_장애에_503과_오류_응답을_반환한다() throws Exception {
        mockMvc.perform(get("/test/service-unavailable"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(4)))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("일시적인 서버 장애가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(true));
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/service-unavailable")
        void unavailable() {
            throw new ServiceUnavailableException();
        }
    }
}
