package com.gustler.backend.collector;

import java.util.List;

public record RouteStops(
    Integer turnSequence,
    List<RouteStop> stops
) {

    public RouteStops {
        stops = List.copyOf(stops);
        validateTurnSequenceIsOneOfStops(turnSequence, stops);
    }

    private static void validateTurnSequenceIsOneOfStops(
        Integer turnSequence,
        List<RouteStop> stops
    ) {
        if (turnSequence == null) {
            return;
        }
        final boolean passesTurnSequence = stops.stream()
            .anyMatch(stop -> stop.stopOrder() == turnSequence);
        if (!passesTurnSequence) {
            throw new IllegalArgumentException(
                "회차 순번 %d 인 정류소를 경유하지 않는다".formatted(turnSequence));
        }
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
