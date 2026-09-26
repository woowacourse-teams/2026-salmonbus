package com.gustler.backend.forecasting.api.quality;

public record TripQualityPreview(String scope, long sampledBatches, long sampledObservations, long sampledAboveRange) { }
