package com.gustler.backend.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository.Reference;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.controller.RouteGeometryController;
import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Shape;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 형상 조건부 요청의 빈값·검증·응답 헤더 공백을 확인하는 회귀 테스트다. */
class GeometryConditionalRequestAuditTest {
    private static final String ROUTE = "204000057";
    private static final String PATH = "/api/v2/routes/204000057/geometry";
    private final RouteGeometryQueryRepository repository = mock(RouteGeometryQueryRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new RouteGeometryController(new RouteGeometryQueryService(repository)))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void 미수집_형상에는_조건부_요청이어도_200과_no_store를_주고_ETag는_주지_않는다() throws Exception {
        // given
        when(repository.reference(ROUTE)).thenReturn(reference());
        when(repository.stops(7)).thenReturn(List.of());
        when(repository.shape(7)).thenReturn(Optional.empty());

        // when
        ResultActions httpResponse = mvc.perform(get(PATH).header("If-None-Match", "*"));

        // then
        httpResponse
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.reason").value("NOT_COLLECTED"))
            .andExpect(jsonPath("$.points").isEmpty())
            .andExpect(jsonPath("$.paths").value(nullValue()))
            .andExpect(jsonPath("$.turningPoint").value(nullValue()))
            .andExpect(jsonPath("$.boundary").value(nullValue()));
    }

    @Test
    void 변경없음_응답도_재검증한_ETag와_캐시_정책을_함께_제공한다() throws Exception {
        // given
        when(repository.reference(ROUTE)).thenReturn(reference());
        when(repository.stops(7)).thenReturn(List.of());
        when(repository.shape(7)).thenReturn(Optional.of(new Shape(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH,
            List.of(new Point(127, 37), new Point(128, 38)))));
        String tag = mvc.perform(get(PATH)).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).isNotNull();

        // when
        ResultActions httpResponse2 = mvc.perform(get(PATH).header("If-None-Match", tag));

        // then
        httpResponse2
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", tag))
            .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public"))
            .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9223372036854775808", "abc"})
    void 잘못된_판본은_ETag보다_먼저_검증하고_저장소에_접근하지_않는다(String referenceVersion)
        throws Exception {
        // when
        ResultActions httpResponse3 = mvc.perform(get(PATH).param("referenceVersionId", referenceVersion).header("If-None-Match", "*"));

        // then
        httpResponse3
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(repository);
    }

    private static Optional<Reference> reference() {
        return Optional.of(new Reference(ROUTE, 7, "3330", "A", "B", null));
    }
}
