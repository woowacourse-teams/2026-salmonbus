package com.gustler.backend.api.board.domain;

import java.util.List;
import java.util.Objects;

/**
 * 한 정류장 방문의 승차 가능 여부와 접근 차량 목록이다.
 * 접근 차량이 없어도 방문 자체는 유지한다.
 */
public record StopState(
    int sequence,
    String stopId,
    String name,
    BoardDirection direction,
    boolean boardingAllowed,
    List<ApproachingVehicle> approachingVehicles
) {
    public StopState {
        Objects.requireNonNull(stopId, "stopId는 null일 수 없습니다.");
        Objects.requireNonNull(name, "name은 null일 수 없습니다.");
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
        Objects.requireNonNull(approachingVehicles, "approachingVehicles는 null일 수 없습니다.");
        approachingVehicles = List.copyOf(approachingVehicles);
    }
}
