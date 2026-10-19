package com.gustler.backend.worker.arrival;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArrivalWaitHeaderTest {
    private final RefreshStationArrivals coordinator = mock(RefreshStationArrivals.class);
    private final ArrivalProperties properties = new ArrivalProperties("test-internal-token", 10000, 8,
        Duration.ofSeconds(40), Duration.ofSeconds(60), Duration.ofSeconds(40), Duration.ofSeconds(300),
        Duration.ofSeconds(40), Duration.ofSeconds(2), Duration.ofMinutes(10));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArrivalRefreshController(coordinator, properties)).build();

    @Test
    void 인증된_요청만_허용한_시간_내에서_수집_완료를_기다린다() throws Exception {
        when(coordinator.requestAndAwait("206000637", Duration.ofMillis(500))).thenReturn(
            new ArrivalRefresh(ArrivalRefresh.Status.IDLE, null, Instant.parse("2026-10-19T03:00:40Z")));
        mvc.perform(post("/internal/arrivals/refresh").header("X-Arrival-Token", "test-internal-token")
            .header(ArrivalRefresh.WAIT_HEADER, "500").contentType(MediaType.APPLICATION_JSON)
            .content("{\"stationId\":\"206000637\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IDLE"));
        verify(coordinator).requestAndAwait("206000637", Duration.ofMillis(500));
        verify(coordinator, never()).request(anyString());
    }

    @Test
    void 인증이_없으면_완료_대기도_시작하지_않는다() throws Exception {
        mvc.perform(post("/internal/arrivals/refresh").header(ArrivalRefresh.WAIT_HEADER, "500")
            .contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"206000637\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(coordinator);
    }

    @Test
    void 음수와_1초를_넘는_완료_대기는_거절한다() throws Exception {
        for (int wait : new int[]{-1, 1001}) {
            mvc.perform(post("/internal/arrivals/refresh").header("X-Arrival-Token", "test-internal-token")
                .header(ArrivalRefresh.WAIT_HEADER, wait).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationId\":\"206000637\"}"))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(coordinator);
    }
}
