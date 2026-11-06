package com.gustler.backend.api.boarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.boarding.dto.BoardingOptionsResponse;
import com.gustler.backend.api.http.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class BoardingOptionsControllerTest {
    private static final String PATH = "/api/v1/routes/204000057/stations/205000231/boarding-options";
    private final BoardingOptionsQueryService service = mock(BoardingOptionsQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BoardingOptionsController(service, new ObjectMapper()))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    private static BoardingOptions result(Integer meters) {
        return result(meters, "동강프라자앞");
    }

    private static BoardingOptions result(Integer meters, String originName) {
        StopVisit origin = new StopVisit("205000231", 5, originName, "UP", true, 127.1487833, 37.4146333);
        StopVisit across = new StopVisit("205000226", 81, "동강프라자", "DOWN", true, 127.14865, 37.4145333);
        return new BoardingOptions("204000057", 7, origin, List.of(new Option(across, 76, StopRelation.OPPOSITE,
            List.of(Access.of(List.of(new Leg(AccessMode.WALK, 98, meters, "PEDESTRIAN_GRAPH")))))));
    }

    @Test
    void 후보와_이동_구간을_문자열_판본과_함께_내보내고_ETag로_재검증한다() throws Exception {
        // when
        when(service.get(any())).thenReturn(result(103));
        MvcResult first = mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceVersionId").value("7"))
            .andExpect(jsonPath("$.origin.stopSequence").value(5))
            .andExpect(jsonPath("$.options[0].stop.direction").value("DOWN"))
            .andExpect(jsonPath("$.options[0].sequenceOffset").value(76))
            .andExpect(jsonPath("$.options[0].relation").value("OPPOSITE"))
            .andExpect(jsonPath("$.options[0].accesses[0].durationSeconds").value(98))
            .andExpect(jsonPath("$.options[0].accesses[0].legs[0].mode").value("WALK"))
            .andExpect(jsonPath("$.options[0].accesses[0].legs[0].distanceMeters").value(103))
            .andExpect(jsonPath("$.options[0].accesses[0].legs[0].source").value("PEDESTRIAN_GRAPH"))
            .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public")).andReturn();
        String tag = first.getResponse().getHeader("ETag");

        // then
        assertThat(tag).startsWith("W/\"b-");
        mvc.perform(get(PATH).header("If-None-Match", tag)).andExpect(status().isNotModified())
            .andExpect(content().string(""));
    }

    @Test
    void 거리가_바뀌면_ETag도_바뀐다() throws Exception {
        // when
        when(service.get(any())).thenReturn(result(103));
        String before = mvc.perform(get(PATH)).andReturn().getResponse().getHeader("ETag");
        when(service.get(any())).thenReturn(result(120));
        String after = mvc.perform(get(PATH).header("If-None-Match", before)).andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");

        // then
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void hashCode가_같아도_정류장_이름이_바뀌면_새_응답을_내보낸다() throws Exception {
        // given
        BoardingOptions before = result(103, "Aa");
        BoardingOptions after = result(103, "BB");
        assertThat(BoardingOptionsResponse.from(before).hashCode())
            .isEqualTo(BoardingOptionsResponse.from(after).hashCode());
        when(service.get(any())).thenReturn(before);
        String oldTag = mvc.perform(get(PATH)).andExpect(status().isOk()).andReturn()
            .getResponse().getHeader("ETag");

        // when
        when(service.get(any())).thenReturn(after);
        String newTag = mvc.perform(get(PATH).header("If-None-Match", oldTag)).andExpect(status().isOk())
            .andExpect(jsonPath("$.origin.name").value("BB")).andReturn().getResponse().getHeader("ETag");

        // then
        assertThat(newTag).isNotEqualTo(oldTag);
    }

    @Test
    void 거리를_모르는_구간은_distanceMeters를_내보내지_않는다() throws Exception {
        // when
        when(service.get(any())).thenReturn(result(null));

        // then
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.options[0].accesses[0].legs[0].distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.options[0].accesses[0].legs[0].durationSeconds").value(98));
    }

    @Test
    void 모르는_이동_수단은_UNSUPPORTED_MODE다() throws Exception {
        // when, then
        mvc.perform(get(PATH).param("modes", "WALK,TELEPORT")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MODE"));
        verify(service, never()).get(any());
    }

    @Test
    void 잘못된_관계와_범위와_노선_ID는_400이다() throws Exception {
        // when, then
        mvc.perform(get(PATH).param("relations", "BEFORE,SIDEWAYS")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(PATH).param("relations", ",")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(PATH).param("maxMinutes", "31")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/routes/2040/stations/205000231/boarding-options")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"));
        verify(service, never()).get(any());
    }
}
