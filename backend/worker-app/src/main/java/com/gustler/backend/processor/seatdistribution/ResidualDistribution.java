package com.gustler.backend.processor.seatdistribution;

/**
 * 중심 좌석에서 얼마나 어긋날지의 분포. 잔차 -40 부터 50 까지 91칸을 낸다.
 *
 * <p>두 겹이다. 먼저 어긋난 방향을 셋으로 가른다. 중심과 같음 · 좌석이 중심보다 적음 ·
 * 중심보다 많음이다. 그다음 방향마다 얼마나 어긋났는지를 크기 묶음 아홉 개로 가른다.
 *
 * <p>한 묶음이 받은 질량은 그 묶음이 담는 정수 잔차에 <b>고르게</b> 나눈다. 묶음 경계가
 * 행마다 다르기 때문에 이렇게만 해도 중심 좌석이 40석인 행과 3석인 행의 "크게 어긋남"이
 * 다른 뜻이 된다.
 *
 * <p>한 방향의 묶음 아홉 개가 모두 미적합이면 그 방향은 적합되지 않은 것으로 보고 확률을
 * 잔차 0 으로 돌린다. 묶음 일부만 미적합이면 그 묶음의 확률만 아주 작은 값으로 두고
 * 나머지 묶음과 같이 정규화한다. 둘은 다른 상태다.
 *
 * <p><b>담을 잔차가 없는 묶음의 몫도 잔차 0 으로 돌린다.</b> 버리고 남은 묶음끼리 다시 나누면
 * 안 된다. 무너지는 묶음은 언제나 작게 어긋나는 쪽이라, 다시 나누면 그 확률이 크게 어긋나는 쪽으로
 * 밀려 올라간다. 중심 좌석이 작을수록 자주 생기는 자리다.
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
            if (!fitted(direction)) {
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
     * <p>앞의 둘만 계수로 낸다. 셋째는 남은 질량이고, 앞의 둘이 1 을 넘어 음수가 되면
     * 아주 작은 양수로 받친 뒤 셋을 다시 정규화한다.
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
        final ResidualDirection direction,
        final int anchorSeats,
        final int capacity
    ) {
        if (direction == ResidualDirection.SEATS_BELOW_ANCHOR) {
            return Math.max(anchorSeats, 1.0);
        }
        return Math.max(capacity - (double) anchorSeats, 1.0);
    }

    private boolean fitted(
        ResidualDirection direction
    ) {
        for (boolean bin : binFitted[direction.ordinal()]) {
            if (bin) {
                return true;
            }
        }
        return false;
    }

    /**
     * 한 방향의 질량을 정수 잔차 칸에 뿌린다.
     *
     * <p>좌석이 중심보다 많은 쪽은 잔차가 음수인데, 크기 묶음은 잔차 격자의 위쪽 끝인 50 까지
     * 잡는다. 그래서 격자 아래쪽 끝인 -40 을 넘어가는 크기가 나온다. 그 확률은 버리지 않고
     * 가장자리 칸에 눌러 담는다.
     *
     * @return 실제로 놓은 확률. 담을 잔차가 없는 묶음이 있으면 방향의 몫보다 작다
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
                final int residual = direction == ResidualDirection.SEATS_BELOW_ANCHOR
                    ? magnitude
                    : -magnitude;
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
            chances[index] = binFitted[direction.ordinal()][index]
                ? ProbabilityScale.chanceOf(
                    LinearSum.of(featureVector, binCoefficients[direction.ordinal()][index]))
                : UNFITTED_BIN_CHANCE;
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
}
