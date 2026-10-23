package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import java.time.Duration;
import java.util.Objects;

/**
 * 탑승판과 차량을 같은 관측 배치로 읽기 위한 기준과 유효 시간을 전달한다.
 */
public record BoardReading(
    Board board,
    long observationBatchId,
    Duration cacheMaxAge
) {
    public BoardReading {
        Objects.requireNonNull(board, "board는 null일 수 없습니다.");
        Objects.requireNonNull(cacheMaxAge, "cacheMaxAge는 null일 수 없습니다.");
    }
}
