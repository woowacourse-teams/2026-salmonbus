package com.gustler.backend.routecatalog.domain;

public record UpstreamRouteStop(
    int stopOrder,
    String stopId,
    String name
) {
}
