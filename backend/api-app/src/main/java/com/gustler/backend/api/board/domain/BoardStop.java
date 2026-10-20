package com.gustler.backend.api.board.domain;

import java.util.Objects;

/**
 * 현재 판본의 한 정류장 방문과 승차 허용 여부다.
 * 같은 정류장 ID의 반복 방문을 순번으로 구분한다.
 */
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
