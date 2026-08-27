package com.gustler.backend.api.board.domain;

import java.util.Objects;

public record BoardStop(
    int sequence,
    String stopId,
    String name,
    BoardDirection direction,
    boolean boardingAllowed
) {

    public BoardStop {
        Objects.requireNonNull(stopId, "stopId는 null일 수 없습니다.");
        Objects.requireNonNull(name, "name은 null일 수 없습니다.");
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
    }
}
