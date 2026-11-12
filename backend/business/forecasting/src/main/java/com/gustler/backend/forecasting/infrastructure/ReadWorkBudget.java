package com.gustler.backend.forecasting.infrastructure;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class ReadWorkBudget {
    private final int maximumUnits;
    private final long maximumNanos;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private int used;

    public ReadWorkBudget(int maximumUnits, Duration maximumDuration) {
        this(maximumUnits, maximumDuration, System::nanoTime);
    }

    ReadWorkBudget(int maximumUnits, Duration maximumDuration, LongSupplier nanoTime) {
        if (maximumUnits < 1 || maximumDuration.isNegative() || maximumDuration.isZero()) {
            throw new IllegalArgumentException("Read budget must be positive");
        }
        this.maximumUnits = maximumUnits;
        this.maximumNanos = maximumDuration.toNanos();
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
    }

    public boolean available() {
        return used < maximumUnits && nanoTime.getAsLong() - startedAt < maximumNanos;
    }

    public boolean tryAcquire() {
        if (!available()) {
            return false;
        }
        used++;
        return true;
    }
}
