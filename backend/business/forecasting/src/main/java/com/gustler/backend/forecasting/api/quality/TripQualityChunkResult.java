package com.gustler.backend.forecasting.api.quality;

public record TripQualityChunkResult(int processedBatches, boolean discoveryCompleted, boolean completed) { }
