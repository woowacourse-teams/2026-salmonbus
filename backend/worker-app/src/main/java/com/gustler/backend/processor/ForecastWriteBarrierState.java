package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class ForecastWriteBarrierState implements InfoContributor {

    private final Clock clock;
    private final AtomicLong skippedCycles = new AtomicLong();
    private final AtomicLong consecutiveSkippedCycles = new AtomicLong();
    private final AtomicReference<Instant> lastSkippedAt = new AtomicReference<>();
    private final AtomicReference<String> lastSkipReason = new AtomicReference<>();

    public ForecastWriteBarrierState(
        Clock clock
    ) {
        this.clock = clock;
    }

    public long recordSkipped(
        String reason
    ) {
        skippedCycles.incrementAndGet();
        lastSkippedAt.set(clock.instant());
        lastSkipReason.set(reason);
        return consecutiveSkippedCycles.incrementAndGet();
    }

    public void recordEntered() {
        consecutiveSkippedCycles.set(0);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            skippedCycles.get(), consecutiveSkippedCycles.get(),
            lastSkippedAt.get(), lastSkipReason.get());
    }

    @Override
    public void contribute(
        Info.Builder builder
    ) {
        Snapshot snapshot = snapshot();
        builder.withDetail("forecastWriteBarrier", Map.of(
            "skippedCycles", snapshot.skippedCycles(),
            "consecutiveSkippedCycles", snapshot.consecutiveSkippedCycles(),
            "lastSkippedAt", snapshot.lastSkippedAt() == null ? "never" : snapshot.lastSkippedAt().toString(),
            "lastSkipReason", snapshot.lastSkipReason() == null ? "none" : snapshot.lastSkipReason()));
    }

    public record Snapshot(
        long skippedCycles,
        long consecutiveSkippedCycles,
        Instant lastSkippedAt,
        String lastSkipReason
    ) {
    }
}
