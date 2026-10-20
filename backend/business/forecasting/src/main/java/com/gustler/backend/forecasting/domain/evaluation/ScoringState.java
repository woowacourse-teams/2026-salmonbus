package com.gustler.backend.forecasting.domain.evaluation;

public enum ScoringState {
    PENDING,
    SETTLED,
    SKIPPED,
    LOST,
    SEAT_MISSING,
    QUALITY_EXCLUDED,
    ;

    public boolean scorable() {
        return this == SETTLED;
    }
}
