package com.gustler.backend.collector;

public record RouteTimetable(
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime
) {
}
