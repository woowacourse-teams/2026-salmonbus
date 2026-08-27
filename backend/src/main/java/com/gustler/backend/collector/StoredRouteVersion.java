package com.gustler.backend.collector;

public record StoredRouteVersion(
    long id,
    RouteVersionContent content
) {
}
