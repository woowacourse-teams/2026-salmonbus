package com.gustler.backend.processor.a18;

import java.util.Arrays;

/**
 * 잔차 분포를 좌석 71칸으로 옮기고 만석 확률로 마감한다.
 *
 * <p>잔차 하나를 좌석 하나로 옮긴다. 중심 좌석에서 잔차를 뺀 값이고, 0석과 정원 사이로 자른다.
 * 자르기 때문에 여러 잔차가 같은 좌석으로 겹쳐 쌓인다.
 *
 * <p>마지막에 0석 칸을 만석 확률로 덮고 1석부터를 남은 질량에 맞춰 다시 잰다. 그래서
 * <b>0석 확률은 정의상 만석 확률과 같다.</b>
 */
final class SeatProbabilityTable {

    private SeatProbabilityTable() {
    }

    static double[] of(
        double[] chancesByResidual,
        final int anchorSeats,
        final int capacity,
        final double fullChance
    ) {
        final int usableCapacity = Math.min(Math.max(capacity, 1), SeatGrid.LARGEST_SEATS);
        double[] scattered = scatter(chancesByResidual, anchorSeats, usableCapacity);
        return closedWithFullChance(normalized(scattered), anchorSeats, usableCapacity, fullChance);
    }

    private static double[] scatter(
        double[] chancesByResidual,
        final int anchorSeats,
        final int usableCapacity
    ) {
        double[] scattered = new double[SeatGrid.SEAT_COUNT];
        for (int index = 0; index < chancesByResidual.length; index++) {
            final int residual = index + SeatGrid.SMALLEST_RESIDUAL;
            final int seats = (int) ProbabilityScale.clip(anchorSeats - residual, 0.0, usableCapacity);
            scattered[seats] += chancesByResidual[index];
        }
        return scattered;
    }

    /** 잔차 쪽에서 이미 합이 1 이지만, 옮기면서 생긴 반올림을 여기서 한 번 더 맞춘다. */
    private static double[] normalized(
        double[] scattered
    ) {
        double sum = 0.0;
        for (final double chance : scattered) {
            sum += chance;
        }
        if (sum <= 0.0) {
            Arrays.fill(scattered, 1.0 / SeatGrid.SEAT_COUNT);
            return scattered;
        }
        for (int seats = 0; seats < scattered.length; seats++) {
            scattered[seats] = scattered[seats] / Math.max(sum, 1e-12);
        }
        return scattered;
    }

    /**
     * 0석 칸에 만석 확률을 놓고 나머지를 1 빼기 만석 확률에 맞춘다.
     *
     * <p>만석이 아닐 질량이 하나도 안 남는 경우가 있다. 중심 좌석이 0석이고 좌석이 중심보다
     * 많아지는 방향이 적합되지 않았을 때다. 그러면 모든 잔차가 0석으로 접힌다. 이때 그대로
     * 두면 만석 확률이 1 이 되어 버려서, 남은 질량을 <b>탈 수 있는 가장 가까운 좌석</b>에 둔다.
     *
     * <p>파이썬 오프라인 채점기는 이 자리에서 0석 확률을 1 로 만든다. 서빙 계약이 다르게
     * 적었고 우리는 서빙 계약을 따른다. 확인이 끝나면 둘 중 하나가 바뀐다.
     */
    private static double[] closedWithFullChance(
        double[] scattered,
        final int anchorSeats,
        final int usableCapacity,
        final double fullChance
    ) {
        double remainder = 0.0;
        for (int seats = 1; seats < scattered.length; seats++) {
            remainder += scattered[seats];
        }

        double[] chances = new double[SeatGrid.SEAT_COUNT];
        if (remainder > 0.0) {
            for (int seats = 1; seats < scattered.length; seats++) {
                chances[seats] = scattered[seats] * (1.0 - fullChance) / remainder;
            }
        } else {
            chances[Math.max(1, Math.min(anchorSeats, usableCapacity))] = 1.0 - fullChance;
        }
        chances[0] = fullChance;

        double sum = 0.0;
        for (final double chance : chances) {
            sum += chance;
        }
        for (int seats = 0; seats < chances.length; seats++) {
            chances[seats] = chances[seats] / sum;
        }
        return chances;
    }
}
