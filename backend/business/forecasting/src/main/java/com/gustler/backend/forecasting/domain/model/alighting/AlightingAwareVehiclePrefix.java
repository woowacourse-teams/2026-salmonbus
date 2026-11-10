package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Instant;

/** 관측 창 이전의 누적 상태와 최대 좌석의 근거 관측이다. 관측을 만들어 대신 넣지 않는다. */
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
