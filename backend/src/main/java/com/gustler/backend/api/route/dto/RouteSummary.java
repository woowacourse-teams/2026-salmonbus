package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.domain.RouteStatus;

public record RouteSummary(
    String id,
    String displayName,
    String startStopName,
    String endStopName,
    RouteStatus status
) {
}
