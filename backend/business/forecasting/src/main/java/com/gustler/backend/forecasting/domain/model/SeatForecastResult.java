package com.gustler.backend.forecasting.domain.model;

/** 분포 모델과 독립 좌석 점예측·출발 만석 확률을 함께 수용한다. */
public record SeatForecastResult(
    SeatDistribution distribution,
    double fullChanceRaw,
    double expectedSeats,
    double fullChance,
    String seatTarget,
    String probabilityTarget
) {

    public static final String LEGACY_SEAT_TARGET = "FIRST_POST_STOP_OBSERVATION";
    public static final String LEGACY_PROBABILITY_TARGET = "FIRST_POST_STOP_ZERO";
    public static final String LES_SEAT_TARGET = "AFTER_ALIGHTING_ESTIMATE";
    public static final String LES_PROBABILITY_TARGET = "DEPARTURE_ZERO";

    public SeatForecastResult(SeatDistribution distribution, final double fullChanceRaw) {
        this(distribution, fullChanceRaw, distribution.expectedSeats(), distribution.fullChance(),
            LEGACY_SEAT_TARGET, LEGACY_PROBABILITY_TARGET);
    }

    public SeatForecastResult {
        if (!hasValidNumbers(expectedSeats, fullChanceRaw, fullChance)) {
            throw new IllegalArgumentException("좌석은 유한한 0 이상, 확률은 0~1이어야 한다");
        }
        final boolean legacy = isLegacyOutput(distribution, seatTarget, probabilityTarget);
        final boolean les = isLesOutput(distribution, seatTarget, probabilityTarget,
            fullChanceRaw, fullChance, expectedSeats);
        if (!legacy && !les) {
            throw new IllegalArgumentException("지원하지 않는 예보 출력 계약이다");
        }
    }

    private static boolean hasValidNumbers(
        final double expectedSeats,
        final double fullChanceRaw,
        final double fullChance) {
        return Double.isFinite(expectedSeats) && expectedSeats >= 0
            && SeatDistribution.isBetweenZeroAndOne(fullChanceRaw)
            && SeatDistribution.isBetweenZeroAndOne(fullChance);
    }

    private static boolean isLegacyOutput(SeatDistribution distribution, String seatTarget, String probabilityTarget) {
        return LEGACY_SEAT_TARGET.equals(seatTarget)
            && LEGACY_PROBABILITY_TARGET.equals(probabilityTarget) && distribution != null;
    }

    private static boolean isLesOutput(
        SeatDistribution distribution,
        String seatTarget,
        String probabilityTarget,
        final double fullChanceRaw,
        final double fullChance,
        final double expectedSeats) {
        return LES_SEAT_TARGET.equals(seatTarget)
            && LES_PROBABILITY_TARGET.equals(probabilityTarget) && distribution == null
            && fullChanceRaw == fullChance && expectedSeats <= 70;
    }

    public static SeatForecastResult les(final double afterAlightingSeats, final double departureFullProbability) {
        return new SeatForecastResult(null, departureFullProbability, afterAlightingSeats,
            departureFullProbability, LES_SEAT_TARGET, LES_PROBABILITY_TARGET);
    }
}
