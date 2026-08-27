package com.gustler.backend.api.board.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class BoardFreshnessPolicy {

    private static final Duration FRESHNESS = Duration.ofMinutes(5);

    private final Clock clock;

    public BoardFreshnessPolicy(Clock clock) {
        this.clock = clock;
    }

    public OffsetDateTime staleAt(OffsetDateTime observedAt) {
        return observedAt.plus(FRESHNESS);
    }

    public boolean isStale(OffsetDateTime observedAt) {
        return OffsetDateTime.now(clock).isAfter(staleAt(observedAt));
    }
}
