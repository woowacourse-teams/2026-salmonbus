package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 커밋된 정산 결과와 진단·보정 적격성 근거다.
 * 좌석 미관측과 0석 도착을 구분한다.
 */
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
    public SettledEvaluation(
        final long routeId,
        final long modelDeploymentId,
        final long routeVersionId,
        final long vehicleObservationId,
        final int targetStopOrder,
        final int stopsToTarget,
        final double rawFullChance,
        ScoringState state,
        Long arrivalObservationId,
        Integer seatsOnArrival,
        Instant arrivedAt,
        Instant scoredAt,
        final boolean usableForCalibration,
        String predictionVehicleId,
        Integer predictionRemainingSeats,
        final boolean targetBoardingAllowed
    ) {
        this(routeId, modelDeploymentId, routeVersionId, vehicleObservationId, targetStopOrder, stopsToTarget,
            rawFullChance,
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
        return Optional.of(
            new SettledForecast(routeId, modelDeploymentId, stopsToTarget, rawFullChance, arrivedAt, seatsOnArrival));
    }
}
