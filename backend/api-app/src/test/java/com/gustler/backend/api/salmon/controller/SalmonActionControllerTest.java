package com.gustler.backend.api.salmon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.salmon.application.SalmonActionService;
import com.gustler.backend.api.salmon.application.SalmonActionSettings;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Coverage;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Listing;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.RiskMath.Risk;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SalmonActionControllerTest {
    private static final String PATH = "/api/v1/routes/204000057/stations/205000231/salmon-action";

    @Test
    void 꺼져_있으면_판정_문장을_내지_않는다() throws Exception {
        MockMvc disabled = mvc(settings(false), Situation.missingStay());

        disabled.perform(get(PATH).param("stopSequence", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DISABLED"))
            .andExpect(jsonPath("$.reasonCode").doesNotExist())
            .andExpect(jsonPath("$.guidance").doesNotExist());
        disabled.perform(get("/api/v1/routes/nope/stations/205000231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DISABLED"));
    }

    @Test
    void 켜져_있고_판정하지_못하면_종료_상태만_싣고_문장은_없다() throws Exception {
        mvc(Situation.missingStay()).perform(get(PATH).param("stopSequence", "5")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.kind").value("DECIDED"))
            .andExpect(jsonPath("$.reasonCode").value("ABSTAIN_EVIDENCE"))
            .andExpect(jsonPath("$.guidance").doesNotExist());
    }

    @Test
    void 켜져_있고_할_행동이_정해지면_문장을_싣는다() throws Exception {
        Situation lowStay = new Situation(true, true, Optional.of(new Risk(0.3, 0.3)), Coverage.UNKNOWN, false,
            Listing.COMPLETE, 0.5, 0, List.of());

        mvc(lowStay).perform(get(PATH).param("stopSequence", "5")).andExpect(status().isOk())
            .andExpect(jsonPath("$.reasonCode").value("NO_MOVE_POLICY"))
            .andExpect(jsonPath("$.guidance").value("지금은 정류장을 옮기지 않아도 돼요."));
    }

    @Test
    void 켜져_있고_정류장_ID나_순번이나_노선_ID의_형식이_틀리면_400이다() throws Exception {
        MockMvc enabled = mvc(Situation.missingStay());

        enabled.perform(get(PATH).param("stopSequence", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        enabled.perform(get("/api/v1/routes/204000057/stations/231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        enabled.perform(get("/api/v1/routes/2040/stations/205000231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"));
    }

    private static MockMvc mvc(Situation situation) {
        return mvc(settings(true), situation);
    }

    private static SalmonActionSettings settings(boolean enabled) {
        return new SalmonActionSettings(enabled, Duration.ofMinutes(15), 0.5, Duration.ofMinutes(20),
            Duration.ofMinutes(20), 0, new BigDecimal("1.2"), 1, Duration.ofSeconds(15));
    }

    private static MockMvc mvc(SalmonActionSettings settings, Situation situation) {
        return MockMvcBuilders.standaloneSetup(new SalmonActionController(
            new SalmonActionService(settings, (routeId, stationId, stopSequence) -> situation)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }
}
