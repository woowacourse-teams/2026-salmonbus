package com.gustler.backend.routecatalog.domain;

import java.util.Objects;

/**
 * 노선의 방향별 첫차·막차와 요일별 배차간격이다.
 * 새 수신에서 해석하지 못한 요일만 기존 정상 값을 유지한다.
 */
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

    /**
     * 새 시간표의 읽을 수 없는 요일별 배차만 이전 판본에서 보완한다. 정상 수신한 시간은 덮어쓰지 않는다.
     */
    public RouteTimetable keepingUnreadableHeadwaysOf(
        RouteTimetable stored
    ) {
        return new RouteTimetable(
            upFirstDepartureTime,
            upLastDepartureTime,
            downFirstDepartureTime,
            downLastDepartureTime,
            headways.keepingUnreadableDaysFrom(storedHeadways(stored)));
    }

    private static RouteHeadways storedHeadways(RouteTimetable stored) {
        if (stored == null) {
            return null;
        }
        return stored.headways();
    }
}
