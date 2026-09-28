package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TripQualityStore {
    Optional<Duration> maximumObservationGap(long routeVersionId);
    Route readRoute(long routeVersionId);
    List<BatchObservations> readPage(long version, String vehicle, Instant at, long batch, boolean backwards, boolean include);
    Map<Long, BatchObservations> observations(long anchor, Long candidate);
    Previous previous(long observationId);
    void saveAssessments(long version, List<OneWayTripAssessment> assessments);
    void applyTimeBudget();
}
