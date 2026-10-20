package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 최신 수집 배치의 결과와 관측 기준 시각이다.
 * 실패·미관측과 정상적인 차량 부재를 구분한다.
 */
public record VehicleSnapshot(
    String routeId,
    String referenceVersionId,
    Long latestBatchId,
    VehiclePollOutcome latestOutcome,
    OffsetDateTime observedAt
) {
    public VehicleSnapshot {
        Objects.requireNonNull(routeId, "routeId는 null일 수 없습니다.");
        Objects.requireNonNull(referenceVersionId, "referenceVersionId는 null일 수 없습니다.");
        Objects.requireNonNull(latestOutcome, "latestOutcome은 null일 수 없습니다.");
    }
}
