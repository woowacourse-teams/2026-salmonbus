package com.gustler.backend.api.board.domain;

/**
 * 정류장 방문에 접근 중인 차량과 남은 정류장 수·좌석 예보를 전달한다.
 *
 * <p>remainingStops는 버스가 있는 정류장(GBIS stationSeq)부터 센 거리다.
 */
public record ApproachingVehicle(
    String vehicleId,
    int remainingStops,
    VehicleForecast forecast
) {
}
