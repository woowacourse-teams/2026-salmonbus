package com.gustler.backend.forecasting.domain.deployment;

public final class ModelActivationConflictException extends RuntimeException {
    public ModelActivationConflictException(String message) {
        super(message);
    }
}
