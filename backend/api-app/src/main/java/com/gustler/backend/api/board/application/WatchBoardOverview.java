package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record WatchBoardOverview(
    Board board,
    List<ObservedVehicle> vehicles,
    Duration cacheMaxAge
) {

    public WatchBoardOverview {
        Objects.requireNonNull(board, "board는 null일 수 없습니다.");
        Objects.requireNonNull(vehicles, "vehicles는 null일 수 없습니다.");
        Objects.requireNonNull(cacheMaxAge, "cacheMaxAge는 null일 수 없습니다.");
        vehicles = List.copyOf(vehicles);
    }
}
