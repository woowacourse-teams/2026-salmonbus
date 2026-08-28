package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import java.time.OffsetDateTime;
import java.util.Objects;

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
