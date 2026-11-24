package com.gustler.backend.api.board.domain;

/**
 * 차량의 승차 시점 좌석 예보다.
 * 제공 가능한 값과 제공할 수 없는 이유를 별도 결과로 표현한다.
 */
public sealed interface VehicleForecast {

    /**
     * 예보가 낸 값이다.
     *
     * <p>{@code departureFullProbability}는 예보가 낸 출발 만석 확률이고 {@code seatAvailableProbability}는 1에서 그 값을 뺀
     * 값이다. 아주 작은 확률이 뺄셈에서 사라지지 않게 둘을 같이 둔다. {@code seatTarget}, {@code probabilityTarget}은 기대
     * 좌석과 확률이 가리키는 관측 기준의 이름이며 공개 응답에 그대로 싣는다.
     */
    record Available(
        double seatAvailableProbability,
        Double expectedSeats,
        String seatTarget,
        String probabilityTarget,
        double departureFullProbability
    ) implements VehicleForecast {

    }

    record Unavailable() implements VehicleForecast {

    }
}
