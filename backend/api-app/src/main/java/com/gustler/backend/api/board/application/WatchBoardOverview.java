package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 같은 관측 기준의 탑승판과 차량을 묶어 전달하며 통합 결과의 캐시 수명을 보존한다.
 */
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
