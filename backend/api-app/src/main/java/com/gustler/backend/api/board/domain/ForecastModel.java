package com.gustler.backend.api.board.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

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
