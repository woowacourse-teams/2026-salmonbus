package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReading;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.route.RouteId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StationBoardReaderTest {
    private static final String FIRST_ROUTE = "234000882";
    private static final String STALE_ROUTE = "214000273";
    private static final String UNPREPARED_ROUTE = "204000081";

    private final BoardQueryService boards = mock(BoardQueryService.class);
    private final Board board = mock(Board.class);
    private final StationBoardReader reader = new StationBoardReader(boards);

    @Test
    void 실패한_노선이_있어도_나머지_노선을_계속_읽고_같은_노선은_한_번만_읽는다() {
        // given
        given(boards.readBoard(new RouteId(FIRST_ROUTE)))
            .willReturn(new BoardReading(board, 1L, Duration.ofSeconds(15)));
        given(boards.readBoard(new RouteId(STALE_ROUTE))).willThrow(new NoRecentObservationException());
        given(boards.readBoard(new RouteId(UNPREPARED_ROUTE))).willThrow(new ModelOutOfScopeException());

        // when
        Map<String, StationBoardReader.BoardLoad> loaded = reader.read(
            List.of(FIRST_ROUTE, STALE_ROUTE, UNPREPARED_ROUTE, FIRST_ROUTE)
        );

        // then
        assertThat(loaded.keySet()).containsExactly(FIRST_ROUTE, STALE_ROUTE, UNPREPARED_ROUTE);
        assertSoftly(softly -> {
            softly.assertThat(loaded.get(FIRST_ROUTE).board()).isSameAs(board);
            softly.assertThat(loaded.get(STALE_ROUTE).failure()).isEqualTo("BOARD_UNAVAILABLE");
            softly.assertThat(loaded.get(UNPREPARED_ROUTE).failure()).isEqualTo("PREPARING");
        });
        then(boards).should(times(1)).readBoard(new RouteId(FIRST_ROUTE));
    }
}
