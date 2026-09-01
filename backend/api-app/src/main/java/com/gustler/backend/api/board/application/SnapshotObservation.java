package com.gustler.backend.api.board.application;

import java.time.OffsetDateTime;
import java.util.Objects;

public record SnapshotObservation(
    long batchId,
    OffsetDateTime observedAt,
    Integer vehiclesInService
) {

    public SnapshotObservation {
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
    }
}
