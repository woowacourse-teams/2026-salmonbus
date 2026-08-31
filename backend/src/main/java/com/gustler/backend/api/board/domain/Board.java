package com.gustler.backend.api.board.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record Board(
    BoardRoute route,
    OffsetDateTime observedAt,
    OffsetDateTime staleAt,
    ForecastModel model,
    int vehiclesInService,
    List<StopState> stops
) {

    public Board {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
        Objects.requireNonNull(staleAt, "staleAt은 null일 수 없습니다.");
        Objects.requireNonNull(model, "model은 null일 수 없습니다.");
        Objects.requireNonNull(stops, "stops는 null일 수 없습니다.");
        stops = List.copyOf(stops);
    }
}
