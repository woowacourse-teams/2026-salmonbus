package com.gustler.backend.routecatalog.domain;

import java.util.Objects;

public record RouteTimetable(
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime,
    RouteHeadways headways
) {

    public RouteTimetable {
        Objects.requireNonNull(headways, "headways는 null일 수 없습니다.");
    }

    public RouteTimetable keepingUnreadableHeadwaysOf(
        RouteTimetable stored
    ) {
        return new RouteTimetable(
            upFirstDepartureTime,
            upLastDepartureTime,
            downFirstDepartureTime,
            downLastDepartureTime,
            headways.keepingUnreadableDaysFrom(stored == null ? null : stored.headways()));
    }
}
