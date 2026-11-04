package com.gustler.backend.api.board.domain;

/**
 * 차량의 승차 시점 좌석 예보다.
 * 제공 가능한 값과 제공할 수 없는 이유를 별도 결과로 표현한다.
 */
public sealed interface VehicleForecast {
    record Available(
        double seatAvailableProbability, Double expectedSeats,
        String seatTarget, String probabilityTarget, Double departureFullProbability
    ) implements VehicleForecast {
        public Available(
            final double seatAvailableProbability,
            Double expectedSeats,
            String seatTarget,
            String probabilityTarget) {
            this(seatAvailableProbability, expectedSeats, seatTarget, probabilityTarget,
                fullProbabilityFromAvailability(probabilityTarget, seatAvailableProbability));
        }

        private static Double fullProbabilityFromAvailability(String target, final double availability) {
            if ("DEPARTURE_ZERO".equals(target)) {
                return 1.0d - availability;
            }
            return null;
        }

        public Available(final double seatAvailableProbability, Double expectedSeats) {
            this(seatAvailableProbability, expectedSeats, "FIRST_POST_STOP_OBSERVATION", "FIRST_POST_STOP_ZERO");
        }
    }

    record Unavailable() implements VehicleForecast {
    }
}
