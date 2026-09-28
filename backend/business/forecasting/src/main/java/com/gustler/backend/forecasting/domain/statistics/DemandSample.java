package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

public record DemandSample(
    long routeVersionId,
    long predictionObservationId,
    long arrivalObservationId,
    String vehicleId,
    int targetStopOrder,
    Instant arrivedAt,
    Instant scoredAt,
    int predictionRemainingSeats,
    int arrivalRemainingSeats
) {

    private static final int ONE_STOP_AHEAD = 1;

    public DemandSample {
        if (vehicleId == null) {
            throw new IllegalArgumentException("통계 입력에는 차량 ID가 필요하다");
        }
        Objects.requireNonNull(arrivedAt, "도착 시각이 필요하다");
        Objects.requireNonNull(scoredAt, "평가 시각이 필요하다");
        if (predictionRemainingSeats < 0 || arrivalRemainingSeats < 0) {
            throw new IllegalArgumentException("잔여석은 0 이상이어야 한다");
        }
    }

    public static Optional<DemandSample> fromSettlement(
        final long routeVersionId,
        final long predictionObservationId,
        final Long arrivalObservationId,
        final String vehicleId,
        final int targetStopOrder,
        final int stopsToTarget,
        final boolean targetBoardingAllowed,
        final Instant arrivedAt,
        final Instant scoredAt,
        final Integer predictionRemainingSeats,
        final Integer seatsOnArrival
    ) {
        if (stopsToTarget != ONE_STOP_AHEAD || !targetBoardingAllowed || vehicleId == null
            || arrivalObservationId == null || predictionRemainingSeats == null || seatsOnArrival == null) {
            return Optional.empty();
        }
        return Optional.of(new DemandSample(routeVersionId, predictionObservationId, arrivalObservationId, vehicleId,
            targetStopOrder, arrivedAt, scoredAt, predictionRemainingSeats, seatsOnArrival));
    }

    public Instant arrivedHourStart() {
        return arrivedAt.truncatedTo(ChronoUnit.HOURS);
    }

    public int netBoarding() {
        return predictionRemainingSeats - arrivalRemainingSeats;
    }
}
