package com.gustler.backend.processor;

/**
 * 한 노선 판본이 지나는 정류장 하나.
 *
 * <p>승차 가능 여부를 같이 든다. 경유 지점은 아무도 타지 않는 고속 구간이라
 * 예보 대상이 되지 못하는데, 그 판정을 배치 코드가 아니라 여기서 한다.
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
