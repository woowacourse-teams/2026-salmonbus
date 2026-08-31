package com.gustler.backend.processor.a18;

/**
 * 크기 묶음 하나가 담는 정수 잔차의 범위.
 *
 * <p>묶음 경계는 상대값이 아니라 <b>정수 좌석 수</b>다. 상대 경계에 배율을 곱해 내림한 값
 * 다음 칸부터, 다음 경계를 내림한 값까지다. 배율은 좌석이 줄어드는 쪽이면 중심 좌석,
 * 늘어나는 쪽이면 정원에서 중심 좌석을 뺀 값이다. 둘 다 1 아래로는 안 내려간다.
 *
 * <p>중심 좌석이 작으면 경계 여럿이 같은 정수로 내려앉아 <b>담을 잔차가 없는 묶음</b>이 생긴다.
 * 그 묶음은 질량을 안 받고, 남은 묶음끼리 다시 나눈다.
 *
 * @param lowest 이 묶음이 담는 가장 작은 잔차 크기
 * @param highest 이 묶음이 담는 가장 큰 잔차 크기
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
     * <p>마지막 묶음의 위 끝은 다음 경계가 없어서 잔차 격자의 끝이다. 그래서 중심 좌석이
     * 1석일 때처럼 배율이 작으면 마지막 묶음 하나가 나머지를 다 담는다.
     */
    static ResidualBinRange[] allOf(
        final double scale,
        double[] relativeEdges
    ) {
        ResidualBinRange[] ranges = new ResidualBinRange[relativeEdges.length];
        for (int index = 0; index < relativeEdges.length; index++) {
            final int lowest = Math.max((int) Math.floor(relativeEdges[index] * scale) + 1, 1);
            final int highest = index + 1 < relativeEdges.length
                ? Math.min((int) Math.floor(relativeEdges[index + 1] * scale), SeatGrid.LARGEST_RESIDUAL)
                : SeatGrid.LARGEST_RESIDUAL;
            ranges[index] = new ResidualBinRange(lowest, highest);
        }
        return ranges;
    }
}
