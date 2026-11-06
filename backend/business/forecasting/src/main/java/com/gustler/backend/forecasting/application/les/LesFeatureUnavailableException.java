package com.gustler.backend.forecasting.application.les;

/** Missing source provenance/scope is a withheld prediction, never an all-zero feature row. */
public final class LesFeatureUnavailableException extends RuntimeException {
    public LesFeatureUnavailableException(String reason) {
        super(reason);
    }
}
