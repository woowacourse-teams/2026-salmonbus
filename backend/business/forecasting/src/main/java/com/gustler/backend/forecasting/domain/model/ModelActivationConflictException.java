package com.gustler.backend.forecasting.domain.model;

public final class ModelActivationConflictException extends RuntimeException {
    public ModelActivationConflictException(String message) {
        super(message);
    }
}
