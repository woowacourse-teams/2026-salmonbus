package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

public record TripQualityStatus(String vehicleId, String phase, boolean completed, Long evidenceObservationId,
    Instant lastBatchAt, long lastBatchId, boolean canRelease, Instant startedAt, Instant investigatedAt) { }
