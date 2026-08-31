package com.gustler.backend.api.board.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BoardFreshnessPolicy {

    private static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(5);

    private final Clock clock;

    public OffsetDateTime staleAt(
        OffsetDateTime observedAt
    ) {
        return observedAt.plus(FRESHNESS_WINDOW);
    }

    public boolean isStale(
        OffsetDateTime observedAt
    ) {
        return OffsetDateTime.now(clock).isAfter(staleAt(observedAt));
    }
}
