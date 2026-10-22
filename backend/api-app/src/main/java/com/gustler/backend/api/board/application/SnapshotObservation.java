package com.gustler.backend.api.board.application;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 탑승판이 기준으로 삼은 관측 배치와 발행 상태다.
 * 서로 다른 배치의 차량과 예보를 섞지 않게 한다.
 */
public record SnapshotObservation(
    long batchId,
    OffsetDateTime observedAt,
    Integer vehiclesInService,
    boolean forecastPublished
) {
    public SnapshotObservation {
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
    }
}
