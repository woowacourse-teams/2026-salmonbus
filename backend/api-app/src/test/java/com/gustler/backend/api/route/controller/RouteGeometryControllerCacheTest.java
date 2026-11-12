package com.gustler.backend.api.route.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.application.RouteDisplayFingerprint;
import com.gustler.backend.api.route.application.RouteGeometryFingerprint;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.application.RouteGeometryResponder;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

class RouteGeometryControllerCacheTest {
    private static final String PATH = "/api/v2/routes/204000057/geometry";
    private static final RouteGeometryFingerprint KEY = new RouteGeometryFingerprint(
            "204000057", "r1", null, null, null, "e", RouteDisplayFingerprint.disabled());

    private final RouteGeometryQueryService service = mock(RouteGeometryQueryService.class);
    private final MockMvc cached = MockMvcBuilders
            .standaloneSetup(new RouteGeometryController(service, new RouteGeometryResponseCache(DataSize.ofMegabytes(32),
                    Duration.ofMinutes(10), Duration.ofSeconds(1), new SimpleMeterRegistry(), System::nanoTime)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final MockMvc plain = MockMvcBuilders
            .standaloneSetup(new RouteGeometryController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final AtomicInteger assembled = new AtomicInteger();

    /** 실제 서비스처럼 지문이 있으면 먼저 재사용을 묻고, 없을 때만 조립한다. 조립 횟수를 센다. */
    private void respondWith(RouteGeometryFingerprint fingerprint, RouteGeometry geometry) {
        when(service.respond(any(), any(), any())).thenAnswer(invocation -> {
            RouteGeometryResponder<?> responder = invocation.getArgument(2);
            if (fingerprint != null) {
                Optional<?> reused = responder.reuse(fingerprint);
                if (reused.isPresent()) {
                    return reused.get();
                }
            }
            assembled.incrementAndGet();
            return responder.create(geometry, fingerprint);
        });
    }

    private static RouteGeometry geometry(final boolean shape) {
        Point a = new Point(127, 37);
        Point b = new Point(127.2, 37.2);
        return new RouteGeometry("204000057", 7, "3330", "A", "B", 2,
                List.of(new Stop("206000637", 1, "A", "UP", true, a), new Stop("206000638", 2, "B", "UP", true, b),
                        new Stop("206000637", 3, "A", "DOWN", true, a)),
                shape ? new Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH,
                        List.of(a, b, a)) : null);
    }

    @Test
    void 지문이_같으면_형상을_다시_조립하지_않고_ETag를_먼저_비교한다() throws Exception {
        respondWith(KEY, geometry(true));
        when(service.get(any(), any())).thenReturn(geometry(true));

        MockHttpServletResponse first = cached.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse();
        MockHttpServletResponse second = cached.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse();
        cached.perform(get(PATH).header("If-None-Match", first.getHeader("ETag")))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""))
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public"));

        assertThat(assembled).hasValue(1);
        verify(service, times(3)).respond(any(), any(), any());
        verify(service, never()).get(any(), any());
        MockHttpServletResponse uncached = plain.perform(get(PATH)).andReturn().getResponse();
        for (MockHttpServletResponse response : List.of(first, second)) {
            assertThat(response.getContentAsString()).isEqualTo(uncached.getContentAsString());
            assertThat(response.getHeader("ETag")).isEqualTo(uncached.getHeader("ETag"));
            assertThat(response.getHeader("Cache-Control")).isEqualTo(uncached.getHeader("Cache-Control"));
        }
    }

    @Test
    void 두지_않는_응답은_요청마다_서비스를_한_번만_불러_한_트랜잭션에서_조립한다() throws Exception {
        respondWith(null, geometry(false));

        for (int request = 0; request < 2; request++) {
            cached.perform(get(PATH).header("If-None-Match", "*")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("ETag"));
        }

        assertThat(assembled).hasValue(2);
        verify(service, times(2)).respond(any(), any(), any());
        verify(service, never()).get(any(), any());
    }

    @Test
    void 지문이_있어도_UNAVAILABLE은_두지_않는다() throws Exception {
        respondWith(KEY, geometry(false));

        cached.perform(get(PATH)).andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"));
        cached.perform(get(PATH)).andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"));

        assertThat(assembled).hasValue(2);
    }

    @Test
    void 진단응답은_캐시를_거치지_않는다() throws Exception {
        when(service.get(any(), any())).thenReturn(geometry(true));

        String tag = cached.perform(get(PATH).param("includeDiagnostics", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        cached.perform(get(PATH).param("includeDiagnostics", "true").header("If-None-Match", tag))
                .andExpect(status().isNotModified());

        verify(service, times(2)).get(any(), any());
        verify(service, never()).respond(any(), any(), any());
    }

    @Test
    void 판본충돌은_캐시보다_먼저_409로_낸다() throws Exception {
        when(service.respond(any(), eq(6L), any())).thenThrow(new RouteGeometryReferenceConflictException());

        cached.perform(get(PATH).param("referenceVersionId", "6").header("If-None-Match", "*"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCE_VERSION_CONFLICT"));

        assertThat(assembled).hasValue(0);
    }
}
