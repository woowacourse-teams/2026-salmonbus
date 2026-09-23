package com.gustler.backend.routecatalog.domain;

public record RouteTimetable(
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime
) {
}
