package com.gustler.backend.forecasting.api.quality;

import java.util.List;

public interface GetTripQualityStatus {
    List<TripQualityStatus> status(long routeVersionId);
}
