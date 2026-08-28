package com.gustler.backend.processor;

/** 차량 한 대의 관측과 그에 딸린 궤적 재료 셋. */
public record VehicleTrajectory(
    ObservedVehicle observation,
    SeatSlope seatSlope,
    PrecedingVehicle precedingVehicle,
    FullSeatStreak fullSeatStreak
) {
}
