package com.gustler.backend.routecatalog.domain;

public record RouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopDirection direction,
    boolean boardingAllowed
) {
}
