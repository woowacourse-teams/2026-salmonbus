package com.gustler.backend.routecatalog.domain;

/**
 * 상류가 보고한 정류장 방문 순번·식별자·이름·좌표 자료다.
 * 검증 후 현재 판본의 업무 값으로 변환한다.
 */
public record UpstreamRouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopCoordinates coordinates
) {
    public UpstreamRouteStop(final int stopOrder, String stopId, String name) {
        this(stopOrder, stopId, name, null);
    }
}
