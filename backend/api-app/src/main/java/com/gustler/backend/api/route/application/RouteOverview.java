package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.CurrentRoute;
import java.util.List;
import java.util.Objects;

public record RouteOverview(
    List<CurrentRoute> routes
) {

    public RouteOverview {
        Objects.requireNonNull(routes, "routes는 null일 수 없습니다.");
        routes = List.copyOf(routes);
    }
}
