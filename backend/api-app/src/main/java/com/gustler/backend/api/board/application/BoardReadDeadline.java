package com.gustler.backend.api.board.application;

import java.time.Duration;

/**
 * 여러 노선 판정보드 읽기의 마감 시각이다. {@link #after}를 부른 때부터 재므로, 호출하는 쪽이 트랜잭션과 연결을 얻기 전에
 * 만들면 연결 풀 대기도 예산에 들어간다.
 */
public final class BoardReadDeadline {

    private static final BoardReadDeadline NONE = new BoardReadDeadline(0, false);

    private final long deadlineNanos;
    private final boolean bounded;

    private BoardReadDeadline(final long deadlineNanos, final boolean bounded) {
        this.deadlineNanos = deadlineNanos;
        this.bounded = bounded;
    }

    /**
     * 지금부터 {@code budget}이 지나면 마감이다.
     */
    public static BoardReadDeadline after(Duration budget) {
        return new BoardReadDeadline(System.nanoTime() + budget.toNanos(), true);
    }

    /**
     * 마감이 없다.
     */
    public static BoardReadDeadline none() {
        return NONE;
    }

    boolean passed() {
        return bounded && System.nanoTime() - deadlineNanos > 0;
    }
}
