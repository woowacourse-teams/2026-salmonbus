package com.gustler.backend.routecatalog.domain;

public record RouteVersionContent(
    RouteContentDigest contentDigest,
    RouteTimetable timetable
) {

    public RouteVersionContent {
        if (timetable.headways().hasUnreadableDays()) {
            throw new IllegalArgumentException("판정할 시간표에 읽지 못한 배차간격이 남아 있다");
        }
    }

    public static RouteVersionContent of(
        RouteStops routeStops,
        RouteTimetable timetable
    ) {
        return new RouteVersionContent(RouteContentDigest.of(routeStops), timetable);
    }

    public RouteVersionDecision decideFor(
        RouteVersionContent incoming
    ) {
        if (!hasSameStopsAs(incoming)) {
            return RouteVersionDecision.OPEN_NEW_VERSION;
        }
        if (!hasSameTimetableAs(incoming)) {
            return RouteVersionDecision.REVISE_TIMETABLE;
        }
        return RouteVersionDecision.KEEP_CURRENT_VERSION;
    }

    private boolean hasSameStopsAs(
        RouteVersionContent other
    ) {
        return contentDigest.equals(other.contentDigest);
    }

    private boolean hasSameTimetableAs(
        RouteVersionContent other
    ) {
        return timetable.equals(other.timetable);
    }
}
