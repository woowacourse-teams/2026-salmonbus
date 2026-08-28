package com.gustler.backend.processor;

/**
 * 차량 한 대의 관측과 그에 딸린 궤적 재료 셋.
 *
 * <p>잔여석이 두 자리에 있다. {@code observation} 이 든 것은 예보에 그대로 넘길 값이고,
 * {@code seats} 는 모를 때 왜 모르는지까지 든다. 적재가 사유를 따로 남겨 뒀는데
 * 읽으면서 잔여석 하나로 합치면 그 사유가 사라진다.
 *
 * <p>관측 행 번호를 같이 든다. 예보 행이 그 관측에 매달리고 행 번호가 seat_forecast 기본키의
 * 일부라, 이 값이 없으면 낸 예보를 어느 관측에서 나온 것으로 저장할지 정할 수 없다.
 */
public record VehicleTrajectory(
    long vehicleObservationId,
    ObservedVehicle observation,
    ObservedSeats seats,
    SeatSlope seatSlope,
    PrecedingVehicle precedingVehicle,
    FullSeatStreak fullSeatStreak
) {
}
