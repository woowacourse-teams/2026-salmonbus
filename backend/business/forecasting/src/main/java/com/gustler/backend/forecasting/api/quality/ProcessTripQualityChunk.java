package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

public interface ProcessTripQualityChunk {
    TripQualityChunkResult applyChunk(long routeVersionId, Instant until, int batchLimit);
}
