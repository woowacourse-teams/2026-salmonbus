package com.gustler.backend.processor;

public record RouteStop(
    long routeVersionId,
    int stopOrder,
    String stopId
) {

    private static final int FIRST_STOP_ORDER = 1;

    public RouteStop {
        if (stopOrder < FIRST_STOP_ORDER) {
            throw new IllegalArgumentException("정류장 순번은 %d번부터다: %d".formatted(FIRST_STOP_ORDER, stopOrder));
        }
    }
}
