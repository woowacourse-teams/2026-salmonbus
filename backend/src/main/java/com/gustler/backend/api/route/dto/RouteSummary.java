package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.Objects;

public record RouteSummary(
    String id,
    String displayName,
    String startStopName,
    String endStopName,
    RouteStatus status
) {

    public RouteSummary {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(displayName, "displayName은 null일 수 없습니다.");
        Objects.requireNonNull(startStopName, "startStopName은 null일 수 없습니다.");
        Objects.requireNonNull(endStopName, "endStopName은 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }

    public static RouteSummary from(
        Route route,
        RouteStatus status
    ) {
        return new RouteSummary(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            status
        );
    }
}
