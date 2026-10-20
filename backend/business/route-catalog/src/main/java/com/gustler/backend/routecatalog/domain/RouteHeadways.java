package com.gustler.backend.routecatalog.domain;

import java.util.Set;

/**
 * 요일별 최소·최대 배차간격을 보관한다.
 * 정보 부재·미해석 상태와 마지막 정상 값의 유지 여부를 구분한다.
 */
public record RouteHeadways(
    HeadwayRange weekday,
    HeadwayRange saturday,
    HeadwayRange sunday,
    HeadwayRange holiday,
    Set<ServiceDay> unreadableDays
) {
    public RouteHeadways {
        unreadableDays = Set.copyOf(unreadableDays);
        for (ServiceDay day : unreadableDays) {
            if (rangeOf(day, weekday, saturday, sunday, holiday) != null) {
                throw new IllegalArgumentException("읽지 못한 요일 %s에 배차간격이 있다".formatted(day));
            }
        }
    }

    public RouteHeadways(
        HeadwayRange weekday,
        HeadwayRange saturday,
        HeadwayRange sunday,
        HeadwayRange holiday
    ) {
        this(weekday, saturday, sunday, holiday, Set.of());
    }

    public static RouteHeadways none() {
        return new RouteHeadways(null, null, null, null);
    }

    public HeadwayRange on(
        ServiceDay day
    ) {
        return rangeOf(day, weekday, saturday, sunday, holiday);
    }

    public boolean hasUnreadableDays() {
        return !unreadableDays.isEmpty();
    }

    /**
     * 이번 수신에서 읽을 수 없는 요일만 이전 배차 간격을 유지한다. 읽은 새 값과 명시적으로 비어 있는 상태는 구별한다.
     */
    public RouteHeadways keepingUnreadableDaysFrom(
        RouteHeadways stored
    ) {
        if (!hasUnreadableDays()) {
            return this;
        }
        return new RouteHeadways(
            readableOn(ServiceDay.WEEKDAY, stored),
            readableOn(ServiceDay.SATURDAY, stored),
            readableOn(ServiceDay.SUNDAY, stored),
            readableOn(ServiceDay.HOLIDAY, stored));
    }

    private HeadwayRange readableOn(
        ServiceDay day,
        RouteHeadways stored
    ) {
        if (!unreadableDays.contains(day)) {
            return on(day);
        }
        if (stored == null) {
            return null;
        }
        return stored.on(day);
    }

    private static HeadwayRange rangeOf(
        ServiceDay day,
        HeadwayRange weekday,
        HeadwayRange saturday,
        HeadwayRange sunday,
        HeadwayRange holiday
    ) {
        return switch (day) {
            case WEEKDAY -> weekday;
            case SATURDAY -> saturday;
            case SUNDAY -> sunday;
            case HOLIDAY -> holiday;
        };
    }
}
