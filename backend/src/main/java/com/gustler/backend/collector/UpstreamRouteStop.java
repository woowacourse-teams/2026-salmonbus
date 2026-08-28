package com.gustler.backend.collector;

public record UpstreamRouteStop(
    int stopOrder,
    String stopId,
    String name
) {
}
