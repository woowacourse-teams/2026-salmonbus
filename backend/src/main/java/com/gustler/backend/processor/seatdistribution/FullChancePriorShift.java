package com.gustler.backend.processor.seatdistribution;

/**
 * 당일 성적으로 만석 확률을 옮긴다.
 *
 * <p>같은 날 이미 도착이 확정된 예보들이 평균보다 자주 만석이었으면 확률을 올리고, 덜 만석이었으면
 * 내린다. 옮기는 폭은 로짓에서 -3 부터 3 까지로 막는다. 하루 성적이 몇 건 안 될 때 한 건이
 * 확률을 끝까지 밀어 버리는 것을 막는 자리다.
 *
 * <p>확정된 예보가 50건보다 적으면 옮기지 않는다. <b>그때도 원값을 그대로 내지 않고</b>
 * 양 끝을 자른 값을 낸다. 안 자르면 저장한 확률이 뒤에서 로짓으로 옮겨질 때 무한대가 된다.
 */
final class FullChancePriorShift {

    /** 이만큼 쌓이기 전에는 안 옮긴다. */
    private static final int SMALLEST_ROW_COUNT = 50;

    /** 당일 성적을 얼마나 믿을지. 이 수만큼의 가상 관측을 평균 쪽에 얹는다. */
    private static final double PRIOR_ROW_COUNT = 200.0;

    /** 로짓에서 옮길 수 있는 폭. */
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
