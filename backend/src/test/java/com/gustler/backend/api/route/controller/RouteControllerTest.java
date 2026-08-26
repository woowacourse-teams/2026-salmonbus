package com.gustler.backend.api.route.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.application.RouteOverview;
import com.gustler.backend.api.route.application.RouteQueryService;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RouteControllerTest {

    private RouteQueryService routeQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        routeQueryService = mock(RouteQueryService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new RouteController(routeQueryService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void 지원_노선을_조회_순서대로_반환한다() throws Exception {
        final Route firstRoute = new Route(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        final Route secondRoute = new Route(
            "234000050",
            "1650",
            "구리수택차고지",
            "안양역"
        );
        given(routeQueryService.getRouteOverview()).willReturn(new RouteOverview(
            List.of(firstRoute, secondRoute),
            RouteStatus.FORECAST_READY
        ));

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(jsonPath("$.routes.length()").value(2))
            .andExpect(jsonPath("$.routes[0].id").value("204000057"))
            .andExpect(jsonPath("$.routes[0].displayName").value("3330"))
            .andExpect(jsonPath("$.routes[0].startStopName").value("도촌동9단지앞"))
            .andExpect(jsonPath("$.routes[0].endStopName").value("안양역"))
            .andExpect(jsonPath("$.routes[0].status").value("FORECAST_READY"))
            .andExpect(jsonPath("$.routes[1].id").value("234000050"));
    }

    @Test
    void DB_장애는_공통_503_오류_응답으로_반환한다() throws Exception {
        given(routeQueryService.getRouteOverview()).willThrow(new ServiceUnavailableException());

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("일시적인 서버 장애가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.retryable").doesNotExist());
    }
}
