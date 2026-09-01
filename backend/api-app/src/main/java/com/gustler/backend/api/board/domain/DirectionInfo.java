package com.gustler.backend.api.board.domain;

import java.util.Objects;

public record DirectionInfo(
    BoardDirection id,
    String name,
    String originStopName,
    String terminalStopName,
    String firstDepartureTime,
    String lastDepartureTime
) {

    public DirectionInfo {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(name, "name은 null일 수 없습니다.");
        Objects.requireNonNull(originStopName, "originStopName은 null일 수 없습니다.");
        Objects.requireNonNull(terminalStopName, "terminalStopName은 null일 수 없습니다.");
        Objects.requireNonNull(firstDepartureTime, "firstDepartureTime은 null일 수 없습니다.");
        Objects.requireNonNull(lastDepartureTime, "lastDepartureTime은 null일 수 없습니다.");
    }
}
