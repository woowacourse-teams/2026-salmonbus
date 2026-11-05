package com.gustler.backend.forecasting.domain.model.les;

import java.time.Instant;

/** 관측 창 이전의 누적 상태와 최대 좌석의 근거 관측이다. 관측을 만들어 대신 넣지 않는다. */
public record LesVehiclePrefix(
    float capacity,
    double fullStreak,
    LesObservation previous,
    Instant entryAt,
    long tripId,
    LesObservation capacityObservation
) {
    public LesVehiclePrefix(
        final float capacity,
        final double fullStreak,
        LesObservation previous,
        Instant entryAt,
        final long tripId
    ) {
        this(capacity, fullStreak, previous, entryAt, tripId, null);
    }
    public LesVehiclePrefix {
        if (hasInvalidCapacity(capacity) || hasInvalidStreak(fullStreak)) {
            throw new IllegalArgumentException("Invalid Les vehicle prefix");
        }
        if (previous == null || entryAt == null) {
            throw new IllegalArgumentException("Invalid Les vehicle prefix");
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
