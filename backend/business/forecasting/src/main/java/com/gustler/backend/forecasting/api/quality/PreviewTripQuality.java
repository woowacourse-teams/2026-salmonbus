package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

public interface PreviewTripQuality {
    TripQualityPreview preview(long routeVersionId, Instant until);
}
