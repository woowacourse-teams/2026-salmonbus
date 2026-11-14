package com.gustler.backend.api.board.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 탑승판이 기준으로 삼은 관측 배치와 발행 상태다.
 * 서로 다른 배치의 차량과 예보를 섞지 않게 한다.
 *
 * <p>{@code forecastSource}는 예보를 읽을 저장 출처이고, {@code forecastDate}는 새 예보 기록에서 그 배치의 날짜
 * 파티션({@code source_date})이다. 날짜는 새 예보 기록 출처에서 예보가 발행된 노선일 때만 있고 그 밖에는 비어 있다.
 */
public record SnapshotObservation(
    long batchId,
    OffsetDateTime observedAt,
    Integer vehiclesInService,
    boolean forecastPublished,
    ForecastSource forecastSource,
    LocalDate forecastDate
) {
    /**
     * 옛 예보 테이블({@link ForecastSource#LEGACY})을 출처로 하는 관측을 만든다.
     */
    public SnapshotObservation(
        final long batchId,
        OffsetDateTime observedAt,
        Integer vehiclesInService,
        final boolean forecastPublished
    ) {
        this(batchId, observedAt, vehiclesInService, forecastPublished, ForecastSource.LEGACY, null);
    }

    public SnapshotObservation {
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
        Objects.requireNonNull(forecastSource, "forecastSource는 null일 수 없습니다.");
        final boolean needsDate = forecastSource == ForecastSource.RECORD && forecastPublished;
        if (needsDate != (forecastDate != null)) {
            throw new IllegalArgumentException("예보 기록의 날짜는 새 예보 기록에서 발행된 관측에만 있어야 합니다.");
        }
    }

    /**
     * 예보를 새 예보 기록({@code forecast_run}·{@code forecast_record})에서 읽어야 하는지 돌려준다.
     */
    public boolean readsForecastRecords() {
        return forecastPublished && forecastSource == ForecastSource.RECORD;
    }
}
