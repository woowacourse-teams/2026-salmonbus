package com.gustler.backend.forecasting.domain.evaluation;

public enum ScoringState {

    PENDING,
    SETTLED,
    SKIPPED,
    LOST,
    SEAT_MISSING,
    ;

    public boolean scorable() {
        return this == SETTLED;
    }
}
