package com.gustler.backend.api.board.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 탑승판이 기준으로 삼은 관측 배치와 발행 상태다.
 * 서로 다른 배치의 차량과 예보를 섞지 않게 한다.
 *
 * <p>{@code forecastDate}는 예보 기록({@code forecast_run}·{@code forecast_record})에서 그 배치의 날짜
 * 파티션({@code source_date})이다. 예보가 발행된 관측에만 있고 그 밖에는 비어 있다.
 */
public record SnapshotObservation(
    long batchId,
    OffsetDateTime observedAt,
    Integer vehiclesInService,
    boolean forecastPublished,
    LocalDate forecastDate
) {
    public SnapshotObservation {
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
        if (forecastPublished != (forecastDate != null)) {
            throw new IllegalArgumentException("예보 기록의 날짜는 예보가 발행된 관측에만 있어야 합니다.");
        }
    }
}
