package com.gustler.backend.api.vehicle.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VehicleFreshnessPolicy {

    private static final Duration STALE_INTERVAL = Duration.ofMinutes(5);

    private final Clock clock;

    public OffsetDateTime staleAt(OffsetDateTime observedAt) {
        return observedAt.plus(STALE_INTERVAL);
    }

    public boolean isStale(OffsetDateTime staleAt) {
        return clock.instant().isAfter(staleAt.toInstant());
    }
}
