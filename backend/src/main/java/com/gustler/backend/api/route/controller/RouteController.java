package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.application.RouteQueryService;
import com.gustler.backend.api.route.dto.RouteListResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/routes")
public class RouteController {

    private static final CacheControl ROUTE_LIST_CACHE = CacheControl
        .maxAge(Duration.ofMinutes(5))
        .cachePublic();

    private final RouteQueryService routeQueryService;

    @GetMapping
    public ResponseEntity<RouteListResponse> listRoutes() {
        RouteListResponse response = RouteListResponse.from(
            routeQueryService.getRouteOverview()
        );

        return ResponseEntity.ok()
            .cacheControl(ROUTE_LIST_CACHE)
            .body(response);
    }
}
