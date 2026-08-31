package com.gustler.backend.processor.a18;

/**
 * 분포의 중심이 될 좌석 수.
 *
 * <p>지금 잔여석이 정원의 몇 할인지를 한 번 접어서 도착 좌석의 중앙값 자리를 잡는다.
 * 계수는 절편과 기울기 둘뿐이다.
 *
 * <p>반올림이 {@link Math#rint} 인 것이 계약이다. {@link Math#round} 는 0.5 를 늘 올리는데
 * 이 자리는 짝수 쪽으로 보낸다. 44석 정원에 22.5석이 나오면 23석이 아니라 22석이다.
 * 정원으로 먼저 자른 뒤에 반올림하는 순서도 그대로다. 순서를 바꾸면 경계에서 한 석 어긋난다.
 */
final class SeatAnchor {

    /** 정원 증거가 0 이어도 나눗셈이 서도록 정원을 이 아래로는 안 내린다. */
    private static final int SMALLEST_CAPACITY = 1;

    private final double intercept;
    private final double slope;

    SeatAnchor(
        final double intercept,
        final double slope
    ) {
        this.intercept = intercept;
        this.slope = slope;
    }

    int seatsOf(
        final int currentSeats,
        final int capacity
    ) {
        final double usableCapacity = Math.max(capacity, SMALLEST_CAPACITY);
        final double located = ProbabilityScale.clip(
            (intercept + slope * (currentSeats / usableCapacity)) * usableCapacity,
            0.0,
            usableCapacity);
        return (int) ProbabilityScale.clip(Math.rint(located), 0.0, SeatGrid.LARGEST_SEATS);
    }
}
