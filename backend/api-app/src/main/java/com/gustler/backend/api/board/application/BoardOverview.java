package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import java.time.Duration;
import java.util.Objects;

public record BoardOverview(
    Board board,
    Duration cacheMaxAge
) {

    public BoardOverview {
        Objects.requireNonNull(board, "board는 null일 수 없습니다.");
        Objects.requireNonNull(cacheMaxAge, "cacheMaxAge는 null일 수 없습니다.");
    }
}
