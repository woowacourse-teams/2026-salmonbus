package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/**
 * 한 노선 버전이 지나는 정류장 하나.
 *
 * <p>승차 가능 여부를 같이 보관한다. 승차할 수 없는 경유 지점은
 * {@link RouteStops}가 예보 대상을 고를 때 제외한다.
 */
public record RouteStop(
    long routeVersionId,
    int stopOrder,
    String stopId,
    boolean boardingAllowed,
    RouteDirection direction
) {

    private static final int FIRST_STOP_ORDER = 1;

    public RouteStop(
        final long routeVersionId,
        final int stopOrder,
        String stopId,
        final boolean boardingAllowed
    ) {
        this(
                routeVersionId,
                stopOrder,
                stopId,
                boardingAllowed,
                RouteDirection.UNKNOWN
        );
    }

    public RouteStop {
        Objects.requireNonNull(direction, "정류장 방향은 UP, DOWN, UNKNOWN 중 하나여야 한다");
        if (stopOrder < FIRST_STOP_ORDER) {
            throw new IllegalArgumentException("정류장 순번은 %d 이상이어야 한다: %d".formatted(FIRST_STOP_ORDER, stopOrder));
        }
    }
}
