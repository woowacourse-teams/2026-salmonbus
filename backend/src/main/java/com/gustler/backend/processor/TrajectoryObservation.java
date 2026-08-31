package com.gustler.backend.processor;

/**
 * 궤적을 잇는 데 쓰는 관측 한 건.
 *
 * <p>여정 키는 예보의 입력이 아니라 관측끼리 이을지 말지를 정하는 재료라 {@link ObservedVehicle} 에
 * 두지 않고 여기서 든다. 지금은 늘 비어 있다. 여정을 어디서 가를지 문턱값이 없어서
 * 적재가 그 열을 안 채운다.
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
