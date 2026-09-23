package com.gustler.backend.forecasting.domain.publication;

/**
 * 한 노선 버전이 지나는 정류장 하나.
 *
 * <p>승차 가능 여부를 함께 보관한다. 승차할 수 없는 경유 지점은
 * {@link RouteStops}가 예보 대상을 고를 때 제외한다.
 */
public record RouteStop(
    long routeVersionId,
    int stopOrder,
    String stopId,
    boolean boardingAllowed
) {

    private static final int FIRST_STOP_ORDER = 1;

    public RouteStop {
        if (stopOrder < FIRST_STOP_ORDER) {
            throw new IllegalArgumentException("정류장 순번은 %d번부터다: %d".formatted(FIRST_STOP_ORDER, stopOrder));
        }
    }
}
