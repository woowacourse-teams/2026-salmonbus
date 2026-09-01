package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import java.util.Objects;

public record RouteOverview(
    List<Route> routes,
    RouteStatus status
) {

    public RouteOverview {
        Objects.requireNonNull(routes, "routes는 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
        routes = List.copyOf(routes);
    }
}
