package com.gustler.backend.forecasting.domain.model.les;

import java.time.Instant;

/** 다섯 필드는 보존한 원관측 이전의 누적 상태다. 관측을 만들어 대신 넣지 않는다. */
public record LesVehiclePrefix(
    float capacity,
    double fullStreak,
    LesObservation previous,
    Instant entryAt,
    long tripId
) {
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
