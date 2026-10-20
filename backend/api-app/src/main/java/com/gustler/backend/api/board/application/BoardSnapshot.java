package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.route.domain.Route;
import java.util.Objects;
import java.util.Optional;

/**
 * 현재 노선 판본과 모델·관측 준비 상태를 함께 읽은 조회 스냅샷이다.
 */
public record BoardSnapshot(
    long routeVersionId,
    Route route,
    Integer turnSequence,
    DepartureSchedule schedule,
    Optional<SnapshotObservation> observation,
    Optional<ForecastModel> activeModel
) {
    public BoardSnapshot {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(schedule, "schedule은 null일 수 없습니다.");
        Objects.requireNonNull(observation, "observation은 null일 수 없습니다.");
        Objects.requireNonNull(activeModel, "activeModel은 null일 수 없습니다.");
    }
}
