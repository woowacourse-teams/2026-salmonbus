package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Instant;

/** 관측 창 이전의 운행 상태다. capacity와 capacityObservation은 과거 체크포인트 읽기 호환에만 남긴다. */
public record AlightingAwareVehiclePrefix(
    float capacity,
    double fullStreak,
    AlightingAwareObservation previous,
    Instant entryAt,
    long tripId,
    AlightingAwareObservation capacityObservation
) {
    public AlightingAwareVehiclePrefix(
        final float capacity,
        final double fullStreak,
        AlightingAwareObservation previous,
        Instant entryAt,
        final long tripId
    ) {
        this(capacity, fullStreak, previous, entryAt, tripId, null);
    }
    public AlightingAwareVehiclePrefix {
        if (hasInvalidCapacity(capacity) || hasInvalidStreak(fullStreak)) {
            throw new IllegalArgumentException("Invalid AlightingAware vehicle prefix");
        }
        if (previous == null || entryAt == null) {
            throw new IllegalArgumentException("Invalid AlightingAware vehicle prefix");
        }
    }

    private static boolean hasInvalidCapacity(final float capacity) {
        if (!Float.isFinite(capacity)) {
            return true;
        }
        return capacity < 0 || capacity > 70;
    }

    private static boolean hasInvalidStreak(final double streak) {
        return !Double.isFinite(streak) || streak < 0;
    }
}
