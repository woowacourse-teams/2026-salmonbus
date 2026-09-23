package com.gustler.backend.forecasting.domain.evaluation;

import com.gustler.backend.forecasting.domain.publication.ObservedVehicle;

import java.time.Instant;

/**
 * 예보 이후 같은 차량의 관측. 대상 정류장을 지난 관측을 평가 결과에 사용한다.
 *
 * <p>평가 결과의 근거를 기록할 수 있도록 관측 ID를 함께 보관한다.
 */
public record ArrivalCandidate(
    long observationId,
    ObservedVehicle vehicle,
    Long qualityDirection,
    boolean qualityAssessed
) {

    public ArrivalCandidate(long observationId, ObservedVehicle vehicle) {
        this(observationId, vehicle, null, true);
    }

    public int passedStopOrder() {
        return vehicle.passedStopOrder();
    }

    public Instant observedAt() {
        return vehicle.observedAt();
    }

    public boolean hasKnownSeats() {
        return vehicle.hasKnownSeats();
    }

    public int remainingSeats() {
        return vehicle.remainingSeats();
    }
}
