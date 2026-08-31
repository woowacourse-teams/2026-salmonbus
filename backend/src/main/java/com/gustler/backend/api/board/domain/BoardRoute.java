package com.gustler.backend.api.board.domain;

import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import java.util.Objects;

public record BoardRoute(
    String id,
    String displayName,
    String startStopName,
    String endStopName,
    RouteStatus status,
    Integer turnSequence,
    List<DirectionInfo> directions,
    String referenceVersionId
) {

    public BoardRoute {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(displayName, "displayName은 null일 수 없습니다.");
        Objects.requireNonNull(startStopName, "startStopName은 null일 수 없습니다.");
        Objects.requireNonNull(endStopName, "endStopName은 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
        Objects.requireNonNull(directions, "directions는 null일 수 없습니다.");
        Objects.requireNonNull(referenceVersionId, "referenceVersionId는 null일 수 없습니다.");
        directions = List.copyOf(directions);
    }
}
