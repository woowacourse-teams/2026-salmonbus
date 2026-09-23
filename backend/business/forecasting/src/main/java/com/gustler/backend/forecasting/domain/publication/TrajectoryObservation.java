package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
/**
 * 궤적을 잇는 데 쓰는 관측 한 건.
 *
 * <p>여정 키는 예보의 입력이 아니라 관측끼리 이을지 말지를 정하는 재료라 {@link ObservedVehicle} 에
 * 두지 않고 여기서 든다. 수집 때는 비어 있으며 품질 판정이 끝나면 forecasting 이 편도 키를 채운다.
 *
 * <p>잔여석도 여기서 사유까지 든다. {@link ObservedVehicle} 은 예보에 넘길 값만 들어서
 * 모르는 사유가 들어갈 자리가 없다.
 */
public record TrajectoryObservation(
    long vehicleObservationId,
    ObservedVehicle vehicle,
    String vehicleTripKey,
    ObservedSeats seats
) {

    private static final int NO_SEAT_LEFT = 0;

    public String vehicleId() {
        return vehicle.vehicleId();
    }

    public int passedStopOrder() {
        return vehicle.passedStopOrder();
    }

    /** 만석인가. 좌석을 모르는 관측은 만석도 아니고 여유도 아니다. */
    public boolean isFull() {
        return seats instanceof ObservedSeats.Known known && known.seats() == NO_SEAT_LEFT;
    }
}
