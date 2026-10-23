package com.gustler.backend.api.board.domain;

/**
 * 정류장 방문에 접근 중인 차량과 정류장 단위 거리·좌석 예보를 전달한다.
 */
public record ApproachingVehicle(
    String vehicleId,
    int horizonStops,
    VehicleForecast forecast
) {
}
