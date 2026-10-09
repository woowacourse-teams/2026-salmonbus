package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SettledEvaluation(
    long routeId,
    long modelDeploymentId,
    long routeVersionId,
    long vehicleObservationId,
    int targetStopOrder,
    int stopsToTarget,
    double rawFullChance,
    ScoringState state,
    Long arrivalObservationId,
    Integer seatsOnArrival,
    Instant arrivedAt,
    Instant scoredAt,
    boolean usableForCalibration,
    String predictionVehicleId,
    Integer predictionRemainingSeats,
    boolean targetBoardingAllowed,
    EvaluationDiagnostics diagnostics
) {

    /** 기존 도메인 호출자는 관측용 부가 정보를 요구하지 않는다. */
    public SettledEvaluation(long routeId, long modelDeploymentId, long routeVersionId, long vehicleObservationId,
        int targetStopOrder, int stopsToTarget, double rawFullChance, ScoringState state,
        Long arrivalObservationId, Integer seatsOnArrival, Instant arrivedAt, Instant scoredAt,
        boolean usableForCalibration, String predictionVehicleId, Integer predictionRemainingSeats,
        boolean targetBoardingAllowed) {
        this(routeId, modelDeploymentId, routeVersionId, vehicleObservationId, targetStopOrder, stopsToTarget, rawFullChance,
            state, arrivalObservationId, seatsOnArrival, arrivedAt, scoredAt, usableForCalibration,
            predictionVehicleId, predictionRemainingSeats, targetBoardingAllowed, null);
    }

    public SettledEvaluation {
        Objects.requireNonNull(state, "평가 상태가 필요하다");
        Objects.requireNonNull(scoredAt, "평가 시각이 필요하다");
        if (state == ScoringState.PENDING) {
            throw new IllegalArgumentException("완료된 평가만 확정 결과가 된다");
        }
    }

    public Optional<SettledForecast> calibrationOutcome() {
        if (!state.scorable() || !usableForCalibration) {
            return Optional.empty();
        }
        return Optional.of(new SettledForecast(routeId, modelDeploymentId, stopsToTarget, rawFullChance, arrivedAt, seatsOnArrival));
    }
}
