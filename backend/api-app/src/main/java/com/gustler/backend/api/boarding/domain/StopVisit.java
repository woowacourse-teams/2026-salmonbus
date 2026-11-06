package com.gustler.backend.api.boarding.domain;

/**
 * 현재 노선 판본의 정류장 방문. 좌표는 최신 수신 좌표이고 없으면 null이다.
 */
public record StopVisit(
    String stationId,
    int stopSequence,
    String name,
    String direction,
    boolean boardingAllowed,
    Double x,
    Double y
) {
}
