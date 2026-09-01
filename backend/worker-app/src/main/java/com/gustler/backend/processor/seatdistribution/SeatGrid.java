package com.gustler.backend.processor.seatdistribution;

/**
 * 좌석과 잔차의 격자.
 *
 * <p>좌석은 0석부터 70석까지 71칸이다. 잔차는 중심 좌석에서 도착 좌석을 뺀 값이고
 * -40 부터 50 까지 91칸이다. 두 격자 폭은 학습 쪽 상수와 같아야 한다.
 */
final class SeatGrid {

    static final int SEAT_COUNT = 71;
    static final int LARGEST_SEATS = SEAT_COUNT - 1;

    static final int SMALLEST_RESIDUAL = -40;
    static final int LARGEST_RESIDUAL = 50;
    static final int RESIDUAL_COUNT = LARGEST_RESIDUAL - SMALLEST_RESIDUAL + 1;

    private SeatGrid() {
    }

    /** 잔차 값이 놓이는 배열 자리. 잔차 -40 이 0번, 잔차 0 이 40번이다. */
    static int residualIndexOf(
        final int residual
    ) {
        return residual - SMALLEST_RESIDUAL;
    }
}
