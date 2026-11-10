package com.gustler.backend.api.salmon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.salmon.application.SalmonActionService;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SalmonActionControllerTest {
    private static final String PATH = "/api/v1/routes/204000057/stations/205000231/salmon-action";

    private final MockMvc enabled = mvc(true);
    private final MockMvc disabled = mvc(false);

    @Test
    void 꺼져_있으면_판정_문장을_내지_않는다() throws Exception {
        disabled.perform(get(PATH).param("stopSequence", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DISABLED"))
            .andExpect(jsonPath("$.reasonCode").doesNotExist())
            .andExpect(jsonPath("$.guidance").doesNotExist());
        disabled.perform(get("/api/v1/routes/nope/stations/205000231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("DISABLED"));
    }

    @Test
    void 켜져_있고_대기_근거가_없으면_근거_부족이다() throws Exception {
        enabled.perform(get(PATH).param("stopSequence", "5")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.kind").value("DECIDED"))
            .andExpect(jsonPath("$.reasonCode").value("ABSTAIN_EVIDENCE"))
            .andExpect(jsonPath("$.guidance").value("이동과 대기를 비교할 근거가 부족해요."));
    }

    @Test
    void 켜져_있고_정류장_형식이_아니면_입력_오류다() throws Exception {
        enabled.perform(get(PATH).param("stopSequence", "0")).andExpect(status().isOk())
            .andExpect(jsonPath("$.reasonCode").value("INVALID_INPUT"));
        enabled.perform(get("/api/v1/routes/204000057/stations/231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reasonCode").value("INVALID_INPUT"));
    }

    @Test
    void 켜져_있고_노선_ID가_아니면_400이다() throws Exception {
        enabled.perform(get("/api/v1/routes/2040/stations/205000231/salmon-action").param("stopSequence", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"));
    }

    private static MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new SalmonActionController(
            new SalmonActionService(enabled, (routeId, stationId, stopSequence) -> Situation.missingStay())))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }
}
