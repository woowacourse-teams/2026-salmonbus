package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.model.SeatGrid;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Decision;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Start;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;

/** 관측 하나를 판정한 결과와 그 결과가 기존 편도에 미치는 변경이다. */
public record OneWayTripAssessment(Observation observation, Previous previous, Decision decision) {
    public static OneWayTripAssessment assess(final Route route, final Previous previous, final Observation current) {
        return new OneWayTripAssessment(current, previous, OneWayTripClassifier.classify(route, previous, current));
    }
    public static boolean requiresInvestigation(final String vehicleId, final Integer seats) {
        return vehicleId != null && !vehicleId.isBlank() && seats != null && seats > SeatGrid.LARGEST_SEATS;
    }
    public boolean startsTrip() { return decision.tripId() == observation.id(); }
    public boolean excludesExistingTrip() {
        return !startsTrip() && decision.status() == Status.EXCLUDED && previous.status() != Status.EXCLUDED;
    }
    public Previous asPrevious() {
        final Start start = previous != null && previous.tripId() == decision.tripId() ? previous.start()
            : new Start(observation.stopOrder(), observation.runningState());
        return new Previous(observation, decision.tripId(), decision.status(), start);
    }
}
