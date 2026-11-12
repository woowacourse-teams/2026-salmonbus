package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.application.BoardBatchRead;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardReadDeadline;
import com.gustler.backend.api.board.application.BoardReading;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.error.ServiceUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StationBoardReaderTest {
    private static final String FIRST_ROUTE = "234000882";
    private static final String STALE_ROUTE = "214000273";
    private static final String UNPREPARED_ROUTE = "204000081";
    private static final String BROKEN_ROUTE = "204000082";

    private final BoardQueryService boards = mock(BoardQueryService.class);
    private final Board board = mock(Board.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StationBoardReader reader = new StationBoardReader(boards, meters);

    @Test
    void 실패한_노선이_있어도_나머지_노선을_그대로_두고_노선_목록을_한_번에_읽는다() {
        // given
        List<String> routeIds = List.of(FIRST_ROUTE, STALE_ROUTE, UNPREPARED_ROUTE, BROKEN_ROUTE, FIRST_ROUTE);
        Map<String, BoardBatchRead> reads = new LinkedHashMap<>();
        reads.put(FIRST_ROUTE, new BoardBatchRead(new BoardReading(board, 1L, Duration.ofSeconds(15)), null));
        reads.put(STALE_ROUTE, BoardBatchRead.failed(new NoRecentObservationException()));
        reads.put(UNPREPARED_ROUTE, BoardBatchRead.failed(new ModelOutOfScopeException()));
        reads.put(BROKEN_ROUTE, BoardBatchRead.failed(new IllegalStateException("broken")));
        given(boards.readBoards(eq(routeIds), any(BoardReadDeadline.class))).willReturn(reads);

        // when
        Map<String, StationBoardReader.BoardLoad> loaded = reader.read(routeIds);

        // then
        assertThat(loaded.keySet()).containsExactly(FIRST_ROUTE, STALE_ROUTE, UNPREPARED_ROUTE, BROKEN_ROUTE);
        assertSoftly(softly -> {
            softly.assertThat(loaded.get(FIRST_ROUTE).board()).isSameAs(board);
            softly.assertThat(loaded.get(STALE_ROUTE).failure()).isEqualTo("BOARD_UNAVAILABLE");
            softly.assertThat(loaded.get(UNPREPARED_ROUTE).failure()).isEqualTo("PREPARING");
            softly.assertThat(loaded.get(BROKEN_ROUTE).failure()).isEqualTo("BOARD_UNAVAILABLE");
            softly.assertThat(meters.counter("salmonbus.station.detail.board.unavailable", "cause", "read").count())
                .isEqualTo(2.0);
        });
        then(boards).should(times(1)).readBoards(anyList(), any());
        then(boards).should(never()).readBoard(any());
    }

    @Test
    void 읽기_상한이_지나_건너뛴_노선은_판정보드_없이_두고_따로_센다() {
        // given
        Map<String, BoardBatchRead> reads = new LinkedHashMap<>();
        reads.put(FIRST_ROUTE, new BoardBatchRead(new BoardReading(board, 1L, Duration.ofSeconds(15)), null));
        reads.put(STALE_ROUTE, new BoardBatchRead(null, null));
        given(boards.readBoards(eq(List.of(FIRST_ROUTE, STALE_ROUTE)), any(BoardReadDeadline.class)))
            .willReturn(reads);

        // when
        Map<String, StationBoardReader.BoardLoad> loaded = reader.read(List.of(FIRST_ROUTE, STALE_ROUTE));

        // then
        assertSoftly(softly -> {
            softly.assertThat(loaded.get(FIRST_ROUTE).board()).isSameAs(board);
            softly.assertThat(loaded.get(STALE_ROUTE).failure()).isEqualTo("BOARD_UNAVAILABLE");
            softly.assertThat(meters.counter("salmonbus.station.detail.board.unavailable", "cause", "budget").count())
                .isEqualTo(1.0);
        });
    }

    @Test
    void 일괄_조회가_실패하면_모든_노선을_판정보드_없이_둔다() {
        // given
        given(boards.readBoards(anyList(), any())).willThrow(new ServiceUnavailableException());

        // when
        Map<String, StationBoardReader.BoardLoad> loaded = reader.read(List.of(FIRST_ROUTE, STALE_ROUTE, FIRST_ROUTE));

        // then
        assertThat(loaded.keySet()).containsExactly(FIRST_ROUTE, STALE_ROUTE);
        assertThat(loaded.values()).extracting(StationBoardReader.BoardLoad::failure)
            .containsOnly("BOARD_UNAVAILABLE");
        assertThat(meters.counter("salmonbus.station.detail.board.unavailable", "cause", "read").count()).isEqualTo(2.0);
    }

    @Test
    void 우리_노선이_없으면_판정보드를_읽지_않는다() {
        // when
        Map<String, StationBoardReader.BoardLoad> loaded = reader.read(List.of());

        // then
        assertThat(loaded).isEmpty();
        then(boards).shouldHaveNoInteractions();
    }
}
