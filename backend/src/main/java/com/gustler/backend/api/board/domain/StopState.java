package com.gustler.backend.api.board.domain;

import java.util.List;
import java.util.Objects;

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
