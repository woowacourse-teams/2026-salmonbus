package com.gustler.backend.api.route.domain;

import java.util.Objects;

public record Route(
    String id,
    String displayName,
    String startStopName,
    String endStopName
) {

    public Route {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(displayName, "displayName은 null일 수 없습니다.");
        Objects.requireNonNull(startStopName, "startStopName은 null일 수 없습니다.");
        Objects.requireNonNull(endStopName, "endStopName은 null일 수 없습니다.");
    }
}
