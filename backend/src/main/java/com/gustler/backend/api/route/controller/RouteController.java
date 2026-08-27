package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.application.RouteQueryService;
import com.gustler.backend.api.route.dto.RouteListResponse;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private static final CacheControl ROUTE_LIST_CACHE = CacheControl
        .maxAge(Duration.ofMinutes(5))
        .cachePublic();

    private final RouteQueryService routeQueryService;

    public RouteController(RouteQueryService routeQueryService) {
        this.routeQueryService = routeQueryService;
    }

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
