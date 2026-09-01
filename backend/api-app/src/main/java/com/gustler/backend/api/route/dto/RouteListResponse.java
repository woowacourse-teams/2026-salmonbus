package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.application.RouteOverview;
import java.util.List;
import java.util.Objects;

public record RouteListResponse(
    List<RouteSummary> routes
) {

    public RouteListResponse {
        Objects.requireNonNull(routes, "routes는 null일 수 없습니다.");
        routes = List.copyOf(routes);
    }

    public static RouteListResponse from(RouteOverview overview) {
        List<RouteSummary> routes = overview.routes()
            .stream()
            .map(route -> RouteSummary.from(route, overview.status()))
            .toList();

        return new RouteListResponse(routes);
    }
}
