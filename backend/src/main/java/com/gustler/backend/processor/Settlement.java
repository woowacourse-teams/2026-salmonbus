package com.gustler.backend.processor;

public enum Settlement {

    PENDING,
    SETTLED,
    SKIPPED,
    LOST,
    SEAT_MISSING;

    public boolean scorable() {
        return this == SETTLED;
    }
}
