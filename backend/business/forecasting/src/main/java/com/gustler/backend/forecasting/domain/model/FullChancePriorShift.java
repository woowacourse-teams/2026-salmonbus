package com.gustler.backend.forecasting.domain.model;

/**
 * 당일 성적으로 만석 확률을 보정한다.
 *
 * <p>같은 날 이미 도착이 확정된 예보들이 평균보다 자주 만석이었으면 확률을 올리고, 덜 만석이었으면
 * 내린다. 보정 폭은 로짓에서 -3 부터 3 까지로 제한해 당일 성적이 적을 때 한 건이
 * 확률을 극단적으로 바꾸지 않도록 한다.
 *
 * <p>확정된 예보가 50건보다 적으면 보정하지 않는다. 이때도 원값의 양 끝을 제한해 반환한다.
 * 제한하지 않으면 저장한 확률을 나중에 로짓으로 변환할 때 무한대가 된다.
 */
final class FullChancePriorShift {
    /** 보정에 필요한 최소 확정 예보 수. */
    private static final int SMALLEST_ROW_COUNT = 50;

    /** 당일 성적의 반영 강도. 평균에 이 수만큼의 가상 관측을 더한다. */
    private static final double PRIOR_ROW_COUNT = 200.0;

    /** 로짓에서 허용하는 보정 폭. */
    private static final double SHIFT_LIMIT = 3.0;

    private FullChancePriorShift() {
    }

    static double shifted(
        final double rawFullChance,
        SameDayFullOutcomes outcomes
    ) {
        final double clipped = ProbabilityScale.clipped(rawFullChance);
        if (outcomes == null || outcomes.rowCount() < SMALLEST_ROW_COUNT) {
            return clipped;
        }
        final double average = ProbabilityScale.clipped(outcomes.averageRawFullChance());
        final double target = ProbabilityScale.clipped(
            (outcomes.actualFullCount() + PRIOR_ROW_COUNT * average)
                / (outcomes.rowCount() + PRIOR_ROW_COUNT));
        final double shift = ProbabilityScale.clip(
            ProbabilityScale.logitOf(target) - ProbabilityScale.logitOf(average),
                -SHIFT_LIMIT,
            SHIFT_LIMIT);
        return ProbabilityScale.chanceOf(ProbabilityScale.logitOf(clipped) + shift);
    }
}
