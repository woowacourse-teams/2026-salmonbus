package com.gustler.backend.api.http;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.NoRecentObservationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({ApiExceptionHandler.class, NoRecentObservationResponseTest.TestController.class})
class NoRecentObservationResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 최근_관측이_없으면_Retry_After_없이_503을_반환한다() throws Exception {
        mockMvc.perform(get("/test/no-recent-observation"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$", aMapWithSize(4)))
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"))
            .andExpect(jsonPath("$.message")
                .value("no vehicle observation recent enough to anchor a forecast"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(true));
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/no-recent-observation")
        void unavailable() {
            throw new NoRecentObservationException();
        }
    }
}
