package com.gustler.backend.worker.arrival;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArrivalRefreshControllerTest {
    private final RefreshStationArrivals coordinator = mock(RefreshStationArrivals.class);
    private final ArrivalProperties properties = new ArrivalProperties("test-internal-token", 10000, 8,
        Duration.ofSeconds(40), Duration.ofSeconds(60), Duration.ofSeconds(40), Duration.ofSeconds(300),
        Duration.ofSeconds(40), Duration.ofSeconds(2), Duration.ofMinutes(10));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArrivalRefreshController(coordinator, properties))
        .build();

    @Test
    void 내부_인증_없이는_작업을_등록하지_않는다() throws Exception {
        // when
        mvc.perform(post("/internal/arrivals/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"stationId\":\"206000637\"}"))
            .andExpect(status().isForbidden());

        // then
        verifyNoInteractions(coordinator);
    }

    @Test
    void 인증된_요청은_완료를_기다리지_않고_접수_결과를_준다() throws Exception {
        // when
        when(coordinator.request("206000637")).thenReturn(new ArrivalRefresh(Status.ACCEPTED,
            "COLLECTING", Instant.parse("2026-10-14T03:00:02Z")));
        mvc.perform(post("/internal/arrivals/refresh").header("X-Arrival-Token", "test-internal-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"206000637\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void 내부_입력도_검증한다() throws Exception {
        // when
        when(coordinator.request("bad")).thenThrow(new IllegalArgumentException());
        mvc.perform(post("/internal/arrivals/refresh").header("X-Arrival-Token", "test-internal-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"bad\"}"))
            .andExpect(status().isBadRequest());
    }
}
