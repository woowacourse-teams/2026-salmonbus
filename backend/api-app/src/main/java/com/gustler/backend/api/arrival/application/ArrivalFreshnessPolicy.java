package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ArrivalFreshnessPolicy {
    private static final long MAX_CACHE_SECONDS = 5;

    public boolean refreshDue(ArrivalSnapshot snapshot, Instant now) {
        return snapshot == null || !now.isBefore(snapshot.refreshAfterAt());
    }

    public boolean usable(ArrivalSnapshot snapshot, Instant now) {
        return snapshot != null && now.isBefore(snapshot.staleAt());
    }

    public Duration cacheMaxAge(ArrivalSnapshot snapshot, Instant now) {
        if (!usable(snapshot, now)) return Duration.ZERO;
        long remaining = Duration.between(now, snapshot.staleAt()).toSeconds();
        long untilRefresh = Duration.between(now, snapshot.refreshAfterAt()).toSeconds();
        return Duration.ofSeconds(Math.max(0, Math.min(MAX_CACHE_SECONDS, Math.min(remaining, untilRefresh))));
    }
}
