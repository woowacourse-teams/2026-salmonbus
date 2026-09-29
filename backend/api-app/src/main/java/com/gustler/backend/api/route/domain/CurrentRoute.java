package com.gustler.backend.api.route.domain;

import java.util.Objects;

public record CurrentRoute(
    Route route,
    RouteStatus status
) {

    public CurrentRoute {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }
}
