package com.gustler.backend.forecasting.application.alighting;

/** Missing source provenance/scope is a withheld prediction, never an all-zero feature row. */
public final class AlightingAwareFeatureUnavailableException extends RuntimeException {
    public AlightingAwareFeatureUnavailableException(String reason) {
        super(reason);
    }
}
