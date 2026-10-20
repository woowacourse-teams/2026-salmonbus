package com.gustler.backend.api.board.domain;

import java.util.Objects;

/**
 * 한 방향의 기점·종점 표시와 첫차·막차 시각을 전달한다.
 */
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
