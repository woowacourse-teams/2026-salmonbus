package com.gustler.backend.api.board.domain;

/**
 * 차량의 승차 시점 좌석 예보다.
 * 제공 가능한 값과 제공할 수 없는 이유를 별도 결과로 표현한다.
 */
public sealed interface VehicleForecast {
    record Available(double seatAvailableProbability, Double expectedSeats) implements VehicleForecast {
    }

    record Unavailable() implements VehicleForecast {
    }
}
