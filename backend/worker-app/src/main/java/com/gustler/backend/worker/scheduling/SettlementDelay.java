package com.gustler.backend.worker.scheduling;

import java.time.Duration;

final class SettlementDelay {
    private final Duration idle;
    private Duration delay = Duration.ZERO;
    private int failures;

    SettlementDelay(Duration idle) {
        if (idle.isZero() || idle.isNegative()) {
            throw new IllegalArgumentException("Settlement interval must be positive");
        }
        this.idle = idle;
    }

    void succeeded(boolean progressed, Duration elapsed) {
        failures = 0;
        delay = progressed ? Duration.ofMillis(Math.max(100, Math.min(idle.toMillis(), elapsed.toMillis() / 4))) : idle;
    }

    void failed() {
        failures = Math.min(failures + 1, 6);
        delay = Duration.ofSeconds(Math.min(60, 1L << failures));
    }

    Duration current() {
        return delay;
    }
}
