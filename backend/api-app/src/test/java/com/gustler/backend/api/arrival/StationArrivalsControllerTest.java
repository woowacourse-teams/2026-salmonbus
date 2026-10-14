package com.gustler.backend.api.arrival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gustler.backend.api.http.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StationArrivalsControllerTest {
    private final ArrivalQueryService service = mock(ArrivalQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StationArrivalsController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    private StationArrivalsResponse response(String status, long age) {
        Instant received = Instant.parse("2026-10-14T03:00:00Z");
        return new StationArrivalsResponse("206000637", received.plusSeconds(age), status, null,
            new StationArrivalsResponse.Snapshot(UUID.randomUUID(), received, null, null,
                received.plusSeconds(40), received.plusSeconds(60)),
            new StationArrivalsResponse.Refresh("IDLE", null, received.plusSeconds(40)), List.of());
    }

    @Test
    void 유효한_정보의_HTTP캐시는_최대5초다() throws Exception {
        when(service.get(any())).thenReturn(response("READY", 10));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=5, public"))
            .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void 갱신경계와_제공불가를_캐시로_가리지_않는다() throws Exception {
        when(service.get(any())).thenReturn(response("READY", 39));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(header().string("Cache-Control", "max-age=1, public"));
        when(service.get(any())).thenReturn(response("UNAVAILABLE", 61));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void 잘못된_식별자는_공통_오류_형식을_사용한다() throws Exception {
        mvc.perform(get("/api/v2/stations/bad/arrivals"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }
}
