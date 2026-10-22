package com.gustler.backend.routecatalog.domain;

/**
 * 판본 교체를 판단할 정류장 구성과 시간표다.
 * 정류장 변경과 시간표만 바뀐 경우를 구분한다.
 */
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

    /**
     * 내용 지문과 기존 판본을 비교해 유지·교체 결정을 만든다. 결정만 계산하며 DB에는 저장하지 않는다.
     */
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
