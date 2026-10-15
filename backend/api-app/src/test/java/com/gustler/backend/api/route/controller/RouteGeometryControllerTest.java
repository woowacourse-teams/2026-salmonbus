package com.gustler.backend.api.route.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.domain.RouteGeometry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RouteGeometryControllerTest {
    private final RouteGeometryQueryService service = mock(RouteGeometryQueryService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new RouteGeometryController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    private static final String PATH = "/api/v2/routes/204000057/geometry";

    private RouteGeometry geometry(double stopX, boolean shape) {
        var point = new RouteGeometry.Point(stopX, 37.1);
        return new RouteGeometry("204000057", 7, "3330", "A", "B", 2,
            List.of(new RouteGeometry.Stop("206000637", 1, "A", "UP", true, point),
                new RouteGeometry.Stop("206000638", 2, "B", "UP", true, new RouteGeometry.Point(127.2,37.2)),
                new RouteGeometry.Stop("206000637", 3, "A", "DOWN", true, point)),
            shape ? new RouteGeometry.Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH,
                List.of(new RouteGeometry.Point(127, 37), new RouteGeometry.Point(127.2, 37.2),
                    new RouteGeometry.Point(127, 37))) : null);
    }

    @Test
    void 네이버식_형상과_반복정류장을_우리_방문순번으로_제공하고_ETag를_재검증한다() throws Exception {
        when(service.get(any(), any())).thenReturn(geometry(127.1, true));
        var first = mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.points[0].x").value(127))
            .andExpect(jsonPath("$.busStops[0].point.y").value(37.1))
            .andExpect(jsonPath("$.busStops[2].stopSequence").value(3))
            .andExpect(jsonPath("$.boundary.left").value(127))
            .andExpect(jsonPath("$.turningPoint.stopSequence").value(2))
            .andExpect(jsonPath("$.coordinateSystem").value("WGS84_ASSUMED"))
            .andExpect(jsonPath("$.turningPoint.pointIdx").doesNotExist())
            .andExpect(jsonPath("$.paths.UP.points[1].x").value(127.2))
            .andExpect(jsonPath("$.paths.DOWN.points[0].x").value(127.2))
            .andExpect(jsonPath("$.paths.DOWN.points[1].x").value(127))
            .andExpect(jsonPath("$.paths.status").doesNotExist())
            .andExpect(jsonPath("$.paths.reason").doesNotExist())
            .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public")).andReturn();
        String tag = first.getResponse().getHeader("ETag");
        assertThat(tag).startsWith("W/\"g-");
        mvc.perform(get(PATH).header("If-None-Match", tag)).andExpect(status().isNotModified()).andExpect(content().string(""));
    }

    @Test
    void 같은_노선판본에서_좌표만_바뀌어도_캐시판본은_달라진다() throws Exception {
        when(service.get(any(), any())).thenReturn(geometry(127.1, true));
        String old = mvc.perform(get(PATH)).andReturn().getResponse().getHeader("ETag");
        when(service.get(any(), any())).thenReturn(geometry(127.11, true));
        var changed = mvc.perform(get(PATH).header("If-None-Match", old)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.paths").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.points.length()").value(3))
            .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public")).andReturn();
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(old);
    }

    @Test
    void 형상이_없으면_정류장_직선을_만들지_않고_판본충돌을_304로_가리지_않는다() throws Exception {
        when(service.get(any(), any())).thenReturn(geometry(127.1, false));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.paths").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.points").isEmpty()).andExpect(header().string("Cache-Control", "no-store"));
        when(service.get(any(), eq(6L))).thenThrow(new RouteGeometryReferenceConflictException());
        mvc.perform(get(PATH).param("referenceVersionId", "6").header("If-None-Match", "*"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCE_VERSION_CONFLICT"));
    }
}
