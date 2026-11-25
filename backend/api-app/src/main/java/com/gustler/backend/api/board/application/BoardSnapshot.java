package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.route.domain.Route;
import java.util.Objects;
import java.util.Optional;

/**
 * 현재 노선 버전과 모델, 관측 준비 상태를 같이 읽은 조회 스냅샷이다.
 *
 * <p>revision은 관측에 붙은 예보 실행 기록의 버전 표시(처리 시작 시각, 예측 수, 모델 배포)다. 같은 관측 회차라도 이 값이 다르면
 * 판정보드가 다르다. 스냅샷이 같으면 판정보드도 같으므로 {@link BoardReadCache}가 이 레코드 전체를 키로 쓴다.
 * 관측이 없거나 조회 경로에서 버전을 읽지 않았으면 null이다.
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
        Objects.requireNonNull(route, "route는 null일 수 없다.");
        Objects.requireNonNull(schedule, "schedule은 null일 수 없다.");
        Objects.requireNonNull(observation, "observation은 null일 수 없다.");
        Objects.requireNonNull(activeModel, "activeModel은 null일 수 없다.");
    }
}
