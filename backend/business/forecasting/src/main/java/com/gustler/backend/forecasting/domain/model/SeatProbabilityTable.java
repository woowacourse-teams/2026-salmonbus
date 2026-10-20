package com.gustler.backend.forecasting.domain.model;

import java.util.Arrays;

/**
 * 잔차 분포를 좌석 71칸의 확률로 변환하고 만석 확률을 반영한다.
 *
 * <p>각 잔차를 중심 좌석에서 빼고 0석과 정원 사이로 제한해 좌석에 대응시킨다.
 * 이 과정에서 여러 잔차의 확률이 같은 좌석에 누적된다.
 *
 * <p>마지막에 0석 칸을 만석 확률로 대체하고 1석부터는 남은 질량에 맞춰 다시 계산한다.
 * 따라서 0석 확률은 정의상 만석 확률과 같다.
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

    /** 잔차 분포의 합은 이미 1 이지만 좌석으로 변환하면서 생긴 반올림 오차를 다시 보정한다. */
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
     * <p>중심 좌석이 0석이고 좌석이 중심보다 많아지는 방향이 적합되지 않았으면
     * 모든 잔차가 0석으로 변환되어 만석이 아닐 질량이 남지 않는다. 그대로 두면 만석 확률이 1 이 되므로
     * 남은 질량을 탈 수 있는 가장 가까운 좌석에 배분한다.
     *
     * <p>파이썬 오프라인 채점기는 이 경우 0석 확률을 1 로 만든다. 서빙 계약이 다르게
     * 규정하므로 여기서는 서빙 계약을 따른다. 확인을 마치면 둘 중 하나를 변경한다.
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
        distributeNonFullMass(chances, scattered, remainder, anchorSeats, usableCapacity, fullChance);
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

    /** 좌석 오름차순의 기존 합산·나눗셈 순서를 보존해 만석 이외의 질량을 배분한다. */
    private static void distributeNonFullMass(
        double[] chances,
        double[] scattered,
        final double remainder,
        final int anchorSeats,
        final int usableCapacity,
        final double fullChance
    ) {
        if (remainder > 0.0) {
            for (int seats = 1; seats < scattered.length; seats++) {
                chances[seats] = scattered[seats] * (1.0 - fullChance) / remainder;
            }
            return;
        }
        chances[Math.max(1, Math.min(anchorSeats, usableCapacity))] = 1.0 - fullChance;
    }
}
