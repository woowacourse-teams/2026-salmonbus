package com.gustler.backend.api.board.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 조회 예보와 연결된 모델 식별과 학습 기준을 전달한다.
 */
public record ForecastModel(
    long deploymentId,
    String releaseId,
    OffsetDateTime trainedThrough
) {
    public ForecastModel {
        Objects.requireNonNull(releaseId, "releaseId는 null일 수 없습니다.");
        Objects.requireNonNull(trainedThrough, "trainedThrough는 null일 수 없습니다.");
    }
}
