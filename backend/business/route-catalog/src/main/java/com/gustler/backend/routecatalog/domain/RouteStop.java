package com.gustler.backend.routecatalog.domain;

public record RouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopDirection direction,
    boolean boardingAllowed,
    StopCoordinates coordinates
) {
    public RouteStop(int stopOrder,
        String stopId,
        String name,
        StopDirection direction, boolean boardingAllowed
    ) {
        this(stopOrder, stopId, name, direction, boardingAllowed, null);
    }
}
