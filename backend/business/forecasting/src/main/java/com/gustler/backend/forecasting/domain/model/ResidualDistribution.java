package com.gustler.backend.forecasting.domain.model;

/**
 * 중심 좌석과의 차이를 나타내는 분포. 잔차 -40 부터 50 까지 91칸을 계산한다.
 *
 * <p>두 단계로 나눈다. 먼저 중심과 같음 · 좌석이 중심보다 적음 · 중심보다 많음의 세 방향으로 나눈 뒤
 * 각 방향에서 어긋난 크기를 아홉 개의 크기 묶음으로 나눈다.
 *
 * <p>한 묶음이 받은 질량은 그 묶음에 포함되는 정수 잔차에 고르게 나눈다. 묶음 경계가
 * 행마다 다르므로 중심 좌석이 40석인 행과 3석인 행에서 크게 어긋난 잔차의 범위도 달라진다.
 *
 * <p>한 방향의 묶음 아홉 개가 모두 미적합이면 그 방향은 적합되지 않은 것으로 보고 확률을
 * 잔차 0 으로 돌린다. 일부만 미적합이면 해당 묶음의 확률만 아주 작은 값으로 두고
 * 나머지 묶음과 함께 정규화한다. 전체 미적합과 일부 미적합은 다르게 처리한다.
 *
 * <p>포함할 잔차가 없는 묶음의 질량도 잔차 0 으로 돌린다. 버리고 남은 묶음끼리 다시 나누면
 * 안 된다. 잔차가 없는 묶음은 항상 작게 어긋나는 쪽이므로 재분배하면 크게 어긋나는 쪽의 확률이
 * 높아진다. 중심 좌석이 작을수록 이런 묶음이 자주 생긴다.
 */
final class ResidualDistribution {
    /** 미적합 묶음이 받는 확률. 0 이 아니라서 방향의 질량이 사라지지 않는다. */
    private static final double UNFITTED_BIN_CHANCE = 1e-6;

    /** 묶음 확률을 정규화할 때 0 으로 나누지 않게 막는 값. */
    private static final double SMALLEST_BIN_SUM = 1e-9;

    private final double[] sameSeatsCoefficients;
    private final double[] belowAnchorCoefficients;
    private final double[][][] binCoefficients;
    private final boolean[][] binFitted;
    private final double[] relativeEdges;

    ResidualDistribution(
        double[] sameSeatsCoefficients,
        double[] belowAnchorCoefficients,
        double[][][] binCoefficients,
        boolean[][] binFitted,
        double[] relativeEdges
    ) {
        this.sameSeatsCoefficients = sameSeatsCoefficients;
        this.belowAnchorCoefficients = belowAnchorCoefficients;
        this.binCoefficients = binCoefficients;
        this.binFitted = binFitted;
        this.relativeEdges = relativeEdges;
    }

    double[] chancesByResidual(
        double[] featureVector,
        final int anchorSeats,
        final int capacity
    ) {
        double[] directionChances = directionChancesOf(featureVector);
        double[] chances = new double[SeatGrid.RESIDUAL_COUNT];
        chances[SeatGrid.residualIndexOf(0)] = directionChances[0];

        for (ResidualDirection direction : ResidualDirection.values()) {
            final double weight = directionChances[direction.ordinal() + 1];
            if (!isFitted(direction)) {
                chances[SeatGrid.residualIndexOf(0)] += weight;
                continue;
            }
            final double allocated = spread(
                chances, featureVector, direction, weight, scaleOf(direction, anchorSeats, capacity));
            if (allocated < weight) {
                chances[SeatGrid.residualIndexOf(0)] += weight - allocated;
            }
        }
        return normalized(chances);
    }

    /**
     * 중심과 같을 확률 · 중심보다 적을 확률 · 중심보다 많을 확률.
     *
     * <p>앞의 두 확률만 계수로 계산한다. 세 번째 확률은 남은 질량이며, 앞의 두 확률을 더해 1 을 넘어
     * 음수가 되면 아주 작은 양수로 보정한 뒤 셋을 다시 정규화한다.
     */
    private double[] directionChancesOf(
        double[] featureVector
    ) {
        final double sameSeats = ProbabilityScale.chanceOf(
            LinearSum.of(featureVector, sameSeatsCoefficients));
        final double belowAnchor = ProbabilityScale.chanceOf(
            LinearSum.of(featureVector, belowAnchorCoefficients));
        final double aboveAnchor = Math.max(
            1.0 - sameSeats - belowAnchor, ProbabilityScale.SMALLEST_CHANCE);
        final double total = sameSeats + belowAnchor + aboveAnchor;
        return new double[] {sameSeats / total, belowAnchor / total, aboveAnchor / total};
    }

    private double scaleOf(
        ResidualDirection direction,
        final int anchorSeats,
        final int capacity
    ) {
        if (direction == ResidualDirection.SEATS_BELOW_ANCHOR) {
            return Math.max(anchorSeats, 1.0);
        }
        return Math.max(capacity - (double) anchorSeats, 1.0);
    }

    private boolean isFitted(
        ResidualDirection direction
    ) {
        for (final boolean bin : binFitted[direction.ordinal()]) {
            if (bin) {
                return true;
            }
        }
        return false;
    }

    /**
     * 한 방향의 질량을 정수 잔차 칸에 배분한다.
     *
     * <p>좌석이 중심보다 많은 방향은 잔차가 음수다. 크기 묶음은 잔차 격자의 위쪽 끝인 50 까지
     * 정하므로 격자 아래쪽 끝인 -40 을 넘어가는 크기가 나온다. 그 확률은 버리지 않고
     * 가장자리 칸에 누적한다.
     *
     * @return 실제로 배분한 확률. 포함할 잔차가 없는 묶음이 있으면 해당 방향의 전체 질량보다 작다
     */
    private double spread(
        double[] chances,
        double[] featureVector,
        ResidualDirection direction,
        final double weight,
        final double scale
    ) {
        double[] binChances = binChancesOf(featureVector, direction);
        ResidualBinRange[] ranges = ResidualBinRange.allOf(scale, relativeEdges);
        double allocated = 0.0;
        for (int index = 0; index < ranges.length; index++) {
            ResidualBinRange range = ranges[index];
            if (!range.usable()) {
                continue;
            }
            allocated += weight * binChances[index];
            final double perResidual = weight * binChances[index] / range.magnitudeCount();
            for (int magnitude = range.lowest(); magnitude <= range.highest(); magnitude++) {
                final int residual = signedResidual(direction, magnitude);
                final int residualIndex =
                    SeatGrid.residualIndexOf(Math.max(residual, SeatGrid.SMALLEST_RESIDUAL));
                chances[residualIndex] += perResidual;
            }
        }
        return allocated;
    }

    private double[] binChancesOf(
        double[] featureVector,
        ResidualDirection direction
    ) {
        double[] chances = new double[relativeEdges.length];
        double sum = 0.0;
        for (int index = 0; index < chances.length; index++) {
            chances[index] = binChance(featureVector, direction, index);
            sum += chances[index];
        }
        final double divisor = Math.max(sum, SMALLEST_BIN_SUM);
        for (int index = 0; index < chances.length; index++) {
            chances[index] = chances[index] / divisor;
        }
        return chances;
    }

    private double[] normalized(
        double[] chances
    ) {
        double sum = 0.0;
        for (int index = 0; index < chances.length; index++) {
            chances[index] = Math.max(chances[index], 0.0);
            sum += chances[index];
        }
        final double divisor = Math.max(sum, 1e-12);
        for (int index = 0; index < chances.length; index++) {
            chances[index] = chances[index] / divisor;
        }
        return chances;
    }

    private static int signedResidual(ResidualDirection direction, final int magnitude) {
        if (direction == ResidualDirection.SEATS_BELOW_ANCHOR) {
            return magnitude;
        }
        return -magnitude;
    }

    private double binChance(double[] featureVector, ResidualDirection direction, final int index) {
        if (binFitted[direction.ordinal()][index]) {
            return ProbabilityScale.chanceOf(
                LinearSum.of(featureVector, binCoefficients[direction.ordinal()][index]));
        }
        return UNFITTED_BIN_CHANCE;
    }
}
