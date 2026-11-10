package com.gustler.backend.api.push.domain;

import java.time.Instant;

public record DeliveryDecision(
    DeliveryStatus status,
    Instant nextAttemptAt
) {
}
