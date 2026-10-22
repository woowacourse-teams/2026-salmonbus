package com.gustler.backend.api.arrival.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.arrival.application.ArrivalFreshnessPolicy;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
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

    private StationArrivalsOverview response(String status, final long age) {
        Instant received = Instant.parse("2026-10-14T03:00:00Z");
        ArrivalSnapshot snapshot = new ArrivalSnapshot(UUID.randomUUID(), "206000637", received,
            received.plusSeconds(40), received.plusSeconds(60), null, List.of());
        Instant now = received.plusSeconds(age);
        return new StationArrivalsOverview("206000637", now, ArrivalAvailability.valueOf(status), null,
            snapshot, new ArrivalRefresh(Status.IDLE, null, received.plusSeconds(40)),
            List.of(), new ArrivalFreshnessPolicy().cacheMaxAge(snapshot, now));
    }

    @Test
    void 유효한_정보의_HTTP캐시는_최대5초다() throws Exception {
        // when
        when(service.get(any())).thenReturn(response("READY", 10));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=5, public"))
            .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void 갱신경계와_제공불가를_캐시로_가리지_않는다() throws Exception {
        // when
        when(service.get(any())).thenReturn(response("READY", 39));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(header().string("Cache-Control", "max-age=1, public"));
        when(service.get(any())).thenReturn(response("UNAVAILABLE", 61));
        mvc.perform(get("/api/v2/stations/206000637/arrivals"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void 잘못된_식별자는_공통_오류_형식을_사용한다() throws Exception {
        // when
        mvc.perform(get("/api/v2/stations/bad/arrivals"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(header().string("Cache-Control", "no-store"));

        // then
        verifyNoInteractions(service);
    }
}
