package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.route.domain.Route;
import java.util.Objects;
import java.util.Optional;

/**
 * 현재 노선 판본과 모델·관측 준비 상태를 함께 읽은 조회 스냅샷이다.
 *
 * <p>revision은 같은 관측 회차 안에서도 판정보드를 바꾸는 값(재발행, 예보 적격 변경)의 판본 표시다.
 * 스냅샷이 같으면 판정보드도 같으므로 {@link BoardReadCache}가 이 레코드 전체를 키로 쓴다.
 * 관측이 없거나 조회 경로에서 판본을 읽지 않았으면 null이다.
 */
public record BoardSnapshot(
    long routeVersionId,
    Route route,
    Integer turnSequence,
    DepartureSchedule schedule,
    Optional<SnapshotObservation> observation,
    Optional<ForecastModel> activeModel,
    String revision
) {
    public BoardSnapshot {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(schedule, "schedule은 null일 수 없습니다.");
        Objects.requireNonNull(observation, "observation은 null일 수 없습니다.");
        Objects.requireNonNull(activeModel, "activeModel은 null일 수 없습니다.");
    }
}
