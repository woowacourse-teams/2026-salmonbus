package com.gustler.backend.forecasting.domain.model;

/**
 * 크기 묶음 하나에 포함되는 정수 잔차의 범위.
 *
 * <p>묶음 경계는 상대값이 아니라 정수 좌석 수다. 상대 경계에 배율을 곱해 내림한 값의
 * 다음 칸부터 다음 경계를 내림한 값까지다. 배율은 좌석이 줄어드는 쪽이면 중심 좌석,
 * 늘어나는 쪽이면 정원에서 중심 좌석을 뺀 값이다. 둘 다 1 아래로는 내려가지 않는다.
 *
 * <p>중심 좌석이 작으면 여러 경계의 내림값이 같아져 포함할 잔차가 없는 묶음이 생긴다.
 * 그 묶음에 배정된 질량은 다른 묶음에 재분배하지 않고 중심 좌석의 확률로 돌린다.
 *
 * @param lowest 이 묶음에 포함되는 가장 작은 잔차 크기
 * @param highest 이 묶음에 포함되는 가장 큰 잔차 크기
 */
record ResidualBinRange(
    int lowest,
    int highest
) {
    boolean usable() {
        return highest >= lowest;
    }

    int magnitudeCount() {
        return highest - lowest + 1;
    }

    /**
     * 배율 하나에 대한 묶음 아홉 개를 만든다.
     *
     * <p>마지막 묶음은 다음 경계가 없으므로 잔차 격자의 최댓값까지 포함한다. 중심 좌석이
     * 1석일 때처럼 배율이 작으면 마지막 묶음 하나가 나머지 잔차를 모두 포함한다.
     */
    static ResidualBinRange[] allOf(
        final double scale,
        double[] relativeEdges
    ) {
        ResidualBinRange[] ranges = new ResidualBinRange[relativeEdges.length];
        for (int index = 0; index < relativeEdges.length; index++) {
            final int lowest = Math.max((int) Math.floor(relativeEdges[index] * scale) + 1, 1);
            final int highest = highestOf(scale, relativeEdges, index);
            ranges[index] = new ResidualBinRange(lowest, highest);
        }
        return ranges;
    }

    private static int highestOf(final double scale, double[] relativeEdges, final int index) {
        if (index + 1 < relativeEdges.length) {
            return Math.min((int) Math.floor(relativeEdges[index + 1] * scale), SeatGrid.LARGEST_RESIDUAL);
        }
        return SeatGrid.LARGEST_RESIDUAL;
    }
}
