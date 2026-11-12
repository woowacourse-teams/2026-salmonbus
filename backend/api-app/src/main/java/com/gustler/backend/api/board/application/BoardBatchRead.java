package com.gustler.backend.api.board.application;

/**
 * 여러 노선을 한 번에 읽을 때 한 노선의 결과다.
 *
 * <p>읽었으면 {@code reading}만, 이 노선만 실패했으면 {@code failure}만 있다.
 * 읽기 예산이 지나 조립하지 않은 노선은 둘 다 없다({@link #skipped()}).
 * {@code failure}는 {@link BoardQueryService#readBoard}가 같은 노선에서 던졌을 예외와 같다.
 */
public record BoardBatchRead(BoardReading reading, RuntimeException failure) {

    private static final BoardBatchRead SKIPPED = new BoardBatchRead(null, null);

    public BoardBatchRead {
        if (reading != null && failure != null) {
            throw new IllegalArgumentException("판정보드와 실패를 함께 담을 수 없습니다.");
        }
    }

    static BoardBatchRead read(BoardReading reading) {
        return new BoardBatchRead(reading, null);
    }

    public static BoardBatchRead failed(RuntimeException failure) {
        return new BoardBatchRead(null, failure);
    }

    static BoardBatchRead skippedByBudget() {
        return SKIPPED;
    }

    public boolean skipped() {
        return reading == null && failure == null;
    }
}
