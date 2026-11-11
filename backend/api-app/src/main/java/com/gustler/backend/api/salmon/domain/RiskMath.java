package com.gustler.backend.api.salmon.domain;

import java.util.List;

/**
 * 조건부 실패 범위와 이득 범위를 계산한다.
 *
 * <p>직접 탑승의 하한은 각 성공 상한을 실패로 뒤집고 곱한 값이고, 상한은 성공 하한을 뒤집어 곱한 값이다.
 * 재탑승의 한 가지 단순식은 반대편 성공과 그 가지의 재탑승 성공을 함께 놓칠 위험이다.
 */
public final class RiskMath {
    private RiskMath() {
    }

    /** 조건부 성공 확률의 닫힌 범위다. */
    public record Chance(double low, double high) {
        public Chance {
            requireUnitInterval(low, high, "chance");
        }
    }

    /** 실패 확률의 닫힌 범위다. */
    public record Risk(double low, double high) {
        public Risk {
            requireUnitInterval(low, high, "risk");
        }
    }

    /** 이득처럼 0 아래로 내려갈 수 있는 닫힌 범위다. */
    public record Bound(double low, double high) {
        public Bound {
            if (!Double.isFinite(low) || !Double.isFinite(high) || low > high) {
                throw new IllegalArgumentException("bound must be finite and ordered");
            }
        }
    }

    public static Risk directFailure(List<Chance> successes) {
        double low = 1;
        double high = 1;
        for (Chance chance : successes) {
            low *= 1 - chance.high();
            high *= 1 - chance.low();
        }
        return new Risk(low, high);
    }

    public static Risk reboardFailure(Chance oppositeSuccess, Chance desiredSuccess) {
        return new Risk(
            1 - oppositeSuccess.high() * desiredSuccess.high(),
            1 - oppositeSuccess.low() * desiredSuccess.low());
    }

    public static Risk marginalPair(double first, double second) {
        requireProbability(first, "first");
        requireProbability(second, "second");
        return new Risk(Math.max(0, 1 - first - second), Math.min(1 - first, 1 - second));
    }

    /**
     * 시도마다 실패할 주변확률만 알 때, 모든 시도가 실패할 확률의 범위다. 시도끼리 독립이라고 보지 않는다.
     * 상한은 가장 작은 실패 확률이고, 하한은 실패 확률의 합에서 (시도 수 - 1)을 뺀 값이다. 시도가 하나면 그 확률 그대로다.
     */
    public static Risk everyAttemptFails(List<Double> failures) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("at least one attempt is required");
        }
        double sum = 0;
        double smallest = 1;
        for (int index = 0; index < failures.size(); index++) {
            double failure = failures.get(index);
            requireProbability(failure, "failure " + index);
            sum += failure;
            smallest = Math.min(smallest, failure);
        }
        return new Risk(Math.min(smallest, Math.max(0, sum - (failures.size() - 1))), smallest);
    }

    public static Bound delta(Risk stay, Risk move) {
        return new Bound(stay.low() - move.high(), stay.high() - move.low());
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must lie in [0,1]");
        }
    }

    private static void requireUnitInterval(double low, double high, String name) {
        if (!Double.isFinite(low) || !Double.isFinite(high) || low < 0 || high > 1 || low > high) {
            throw new IllegalArgumentException(name + " must lie in [0,1]");
        }
    }
}
