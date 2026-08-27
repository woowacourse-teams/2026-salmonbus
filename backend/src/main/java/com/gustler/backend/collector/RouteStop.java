package com.gustler.backend.collector;

public record RouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopDirection direction,
    boolean boardingAllowed
) {
}
