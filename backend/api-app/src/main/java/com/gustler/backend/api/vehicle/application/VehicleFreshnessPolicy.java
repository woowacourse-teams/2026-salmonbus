package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.observation.ObservationFreshness;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VehicleFreshnessPolicy {

    private final Clock clock;

    public OffsetDateTime staleAt(OffsetDateTime observedAt) {
        return ObservationFreshness.staleAt(observedAt);
    }

    public boolean isStale(OffsetDateTime staleAt) {
        return clock.instant().isAfter(staleAt.toInstant());
    }
}
