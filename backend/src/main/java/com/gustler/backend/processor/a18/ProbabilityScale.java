package com.gustler.backend.processor.a18;

/**
 * 확률과 로짓 사이를 오간다.
 *
 * <p>확률을 로짓으로 옮기면 0 과 1 에서 무한대가 된다. 그래서 옮기기 전에 양 끝을 잘라 두고,
 * 돌아올 때도 지수의 입력을 잘라 둔다. 자르는 폭은 학습 쪽 상수와 같아야 한다.
 * 여기서 넓히면 같은 계수로 다른 확률이 나온다.
 */
final class ProbabilityScale {

    /** 로짓으로 옮기기 전에 확률을 이 폭 안으로 자른다. */
    static final double SMALLEST_CHANCE = 1e-6;

    /** 지수의 입력이 이 밖으로 나가면 확률이 0 이나 1 로 포화된다. */
    private static final double LOGIT_LIMIT = 30.0;

    private ProbabilityScale() {
    }

    static double clipped(
        final double chance
    ) {
        return Math.min(1.0 - SMALLEST_CHANCE, Math.max(SMALLEST_CHANCE, chance));
    }

    static double logitOf(
        final double chance
    ) {
        final double clipped = clipped(chance);
        return Math.log(clipped / (1.0 - clipped));
    }

    /** 로짓을 확률로 되돌린다. 입력을 먼저 자르므로 결과가 0 이나 1 이 되지 않는다. */
    static double chanceOf(
        final double logit
    ) {
        return 1.0 / (1.0 + Math.exp(-clip(logit, -LOGIT_LIMIT, LOGIT_LIMIT)));
    }

    static double clip(
        final double value,
        final double lowest,
        final double highest
    ) {
        return Math.min(highest, Math.max(lowest, value));
    }
}
