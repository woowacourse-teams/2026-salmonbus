package com.gustler.backend.api.board.domain;

/**
 * 그 정류장으로 오고 있는 차량 한 대.
 *
 * <p>좌석을 아는 차량과 모르는 차량을 타입으로 나눈다. 상류가 잔여석을 안 줬거나 예보를 못 낸
 * 차량도 보드에서 빼지 않고 모른다고 말한다.
 */
public sealed interface ApproachingVehicle
    permits ApproachingVehicle.Forecast, ApproachingVehicle.SeatUnknown {

    String vehicleId();

    int horizonStops();

    /** 예보를 낸 차량. 좌석이 있을 확률을 같이 낸다. */
    record Forecast(
        String vehicleId,
        int horizonStops,
        double seatAvailableProbability,
        Double expectedSeats
    ) implements ApproachingVehicle {
    }

    /** 예보를 못 낸 차량. 오고 있다는 것만 말하고 좌석은 모른다고 답한다. */
    record SeatUnknown(
        String vehicleId,
        int horizonStops
    ) implements ApproachingVehicle {
    }
}
