package com.gustler.backend.api.push.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class DeliveryPolicy {
    public static final Duration LEASE = Duration.ofMinutes(1);
    public static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);
    public static final Duration RETRY_WINDOW = Duration.ofMinutes(2);

    private DeliveryPolicy() {
    }

    public static Instant expiryCutoff(Instant now) {
        return now.truncatedTo(ChronoUnit.MINUTES).minus(RETRY_WINDOW);
    }

    public static boolean expired(Instant occurrenceAt, Instant now) {
        return occurrenceAt.isBefore(expiryCutoff(now));
    }

    public static DeliveryDecision decide(DeliveryOutcome outcome, Instant occurrenceAt, Instant now) {
        return switch (outcome) {
            case SENT -> new DeliveryDecision(DeliveryStatus.SENT, now);
            case CONFIGURATION_ERROR -> new DeliveryDecision(DeliveryStatus.GAVE_UP, now);
            case RETRYABLE -> afterRetryableFailure(occurrenceAt, now);
            case TOKEN_REJECTED -> throw new IllegalArgumentException();
        };
    }

    private static DeliveryDecision afterRetryableFailure(Instant occurrenceAt, Instant now) {
        Instant next = now.truncatedTo(ChronoUnit.MINUTES).plus(RETRY_INTERVAL);
        if (next.isAfter(occurrenceAt.plus(RETRY_WINDOW))) {
            return new DeliveryDecision(DeliveryStatus.GAVE_UP, now);
        }
        return new DeliveryDecision(DeliveryStatus.PENDING, next);
    }
}
