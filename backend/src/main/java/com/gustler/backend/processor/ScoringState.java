package com.gustler.backend.processor;

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
