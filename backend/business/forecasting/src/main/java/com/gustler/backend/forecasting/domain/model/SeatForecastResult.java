package com.gustler.backend.forecasting.domain.model;

/**
 * 목표 정류장 하나의 예측이다. 하차 후 좌석 추정값과 그 정류장을 출발할 때 0석일 확률을 서로 독립된 값으로 담는다.
 *
 * @param expectedSeats 하차 후 좌석 추정값. 0 이상 70 이하의 유한한 수다
 * @param fullChance 출발할 때 0석일 확률. 0 이상 1 이하다
 * @param seatTarget 좌석 값의 뜻. {@link #ALIGHTING_AWARE_SEAT_TARGET}만 받는다
 * @param probabilityTarget 확률 값의 뜻. {@link #ALIGHTING_AWARE_PROBABILITY_TARGET}만 받는다
 */
public record SeatForecastResult(
    double expectedSeats,
    double fullChance,
    String seatTarget,
    String probabilityTarget
) {

    public static final String ALIGHTING_AWARE_SEAT_TARGET = "AFTER_ALIGHTING_ESTIMATE";
    public static final String ALIGHTING_AWARE_PROBABILITY_TARGET = "DEPARTURE_ZERO";

    private static final double MAXIMUM_SEATS = 70;

    public SeatForecastResult {
        if (!hasValidNumbers(expectedSeats, fullChance)) {
            throw new IllegalArgumentException("좌석은 0 이상 70 이하의 유한한 수, 확률은 0~1이어야 한다");
        }
        if (!ALIGHTING_AWARE_SEAT_TARGET.equals(seatTarget)
            || !ALIGHTING_AWARE_PROBABILITY_TARGET.equals(probabilityTarget)) {
            throw new IllegalArgumentException("지원하지 않는 예보 출력 계약이다");
        }
    }

    public static SeatForecastResult alighting(
        final double afterAlightingSeats,
        final double departureFullProbability
    ) {
        return new SeatForecastResult(
                afterAlightingSeats,
                departureFullProbability,
                ALIGHTING_AWARE_SEAT_TARGET,
                ALIGHTING_AWARE_PROBABILITY_TARGET
        );
    }

    private static boolean hasValidNumbers(final double expectedSeats, final double fullChance) {
        return hasValidExpectedSeats(expectedSeats) && hasValidFullChance(fullChance);
    }

    private static boolean hasValidExpectedSeats(final double expectedSeats) {
        return Double.isFinite(expectedSeats) && expectedSeats >= 0 && expectedSeats <= MAXIMUM_SEATS;
    }

    private static boolean hasValidFullChance(final double fullChance) {
        return Double.isFinite(fullChance) && fullChance >= 0 && fullChance <= 1;
    }
}
