package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 예보를 낸 뒤 그 차량이 남긴 관측 하나. 이 중 대상 정류장를 지난 것이 라벨이 된다.
 *
 * <p>회수한 라벨은 어느 관측에서 왔는지를 예보 행에 남겨야 해서 관측의 행 번호를 같이 든다.
 */
public record ArrivalCandidate(
    long observationId,
    ObservedVehicle vehicle
) {

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
