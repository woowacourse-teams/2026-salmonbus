package com.gustler.backend.routecatalog.domain;

/**
 * 노선 판본 안의 한 정류장 방문이다.
 * 방문 순번·방향·승차 허용 여부를 원천 정류장 식별과 함께 보관한다.
 */
public record RouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopDirection direction,
    boolean boardingAllowed,
    StopCoordinates coordinates
) {
    public RouteStop(
        final int stopOrder,
        String stopId,
        String name,
        StopDirection direction,
        final boolean boardingAllowed
    ) {
        this(stopOrder, stopId, name, direction, boardingAllowed, null);
    }
}
