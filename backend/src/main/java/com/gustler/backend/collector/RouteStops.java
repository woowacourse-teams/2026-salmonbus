package com.gustler.backend.collector;

import java.util.List;

public record RouteStops(
    Integer turnSequence,
    List<RouteStop> stops
) {

    public RouteStops {
        stops = List.copyOf(stops);
    }

    public static RouteStops from(
        Integer turnSequence,
        List<UpstreamRouteStop> upstreamStops
    ) {
        return new RouteStops(
            turnSequence,
            upstreamStops.stream()
                .map(upstreamStop -> toRouteStop(upstreamStop, turnSequence))
                .toList()
        );
    }

    private static RouteStop toRouteStop(
        UpstreamRouteStop upstreamStop,
        Integer turnSequence
    ) {
        return new RouteStop(
            upstreamStop.stopOrder(),
            upstreamStop.stopId(),
            upstreamStop.name(),
            directionOf(upstreamStop.stopOrder(), turnSequence),
            BoardingPolicy.allowsBoardingAt(upstreamStop.stopId())
        );
    }

    private static StopDirection directionOf(
        final int stopOrder,
        Integer turnSequence
    ) {
        if (turnSequence == null || stopOrder <= turnSequence) {
            return StopDirection.UP;
        }
        return StopDirection.DOWN;
    }
}
