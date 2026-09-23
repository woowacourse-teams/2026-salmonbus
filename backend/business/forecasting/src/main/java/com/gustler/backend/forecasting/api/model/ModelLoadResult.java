package com.gustler.backend.forecasting.api.model;

public record ModelLoadResult(Status status, Long deploymentId, long activeVersion) {
    public enum Status { NOT_CONFIGURED, READY, ACTIVATED, IDENTITY_MISMATCH, ACTIVATION_CONFLICT }
}
