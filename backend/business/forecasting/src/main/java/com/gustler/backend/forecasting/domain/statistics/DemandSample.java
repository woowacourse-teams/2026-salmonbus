package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * 정산에서 얻은 통계 표본이다.
 * 도착 시간대와 순승차량 계산을 같은 원본 값에서 제공한다.
 */
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
        Long arrivalObservationId,
        String vehicleId,
        final int targetStopOrder,
        final int stopsToTarget,
        final boolean targetBoardingAllowed,
        Instant arrivedAt,
        Instant scoredAt,
        Integer predictionRemainingSeats,
        Integer seatsOnArrival
    ) {
        if (!isEligibleSettlement(stopsToTarget, targetBoardingAllowed)) {
            return Optional.empty();
        }
        if (!hasRequiredValues(vehicleId, arrivalObservationId, predictionRemainingSeats, seatsOnArrival)) {
            return Optional.empty();
        }
        return Optional.of(new DemandSample(routeVersionId, predictionObservationId, arrivalObservationId, vehicleId,
            targetStopOrder, arrivedAt, scoredAt, predictionRemainingSeats, seatsOnArrival));
    }

    private static boolean isEligibleSettlement(int stopsToTarget, boolean targetBoardingAllowed) {
        return stopsToTarget == ONE_STOP_AHEAD && targetBoardingAllowed;
    }

    private static boolean hasRequiredValues(
        String vehicleId,
        Long arrivalObservationId,
        Integer predictionRemainingSeats,
        Integer seatsOnArrival
    ) {
        if (vehicleId == null || arrivalObservationId == null) {
            return false;
        }
        return predictionRemainingSeats != null && seatsOnArrival != null;
    }

    public Instant arrivedHourStart() {
        return arrivedAt.truncatedTo(ChronoUnit.HOURS);
    }

    public int netBoarding() {
        return predictionRemainingSeats - arrivalRemainingSeats;
    }
}
