package com.gustler.backend.api.push.domain;

public enum DeliveryOutcome {
    SENT,
    TOKEN_REJECTED,
    RETRYABLE,
    CONFIGURATION_ERROR
}
