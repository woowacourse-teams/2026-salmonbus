package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReverseBoundarySearchTest {
    private static final Instant AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final Route ROUTE = new Route(1, 7, 4, null);

    @ParameterizedTest
    @CsvSource({"2,0,2,1", "1,0,2,3", "1,0,1,4", "2,0,0,1", "0,2,1,2", "0,1,0,4"})
    void 회차지의_반복_상태를_확인해_첫_출발을_선택하고_출발이_없으면_회차지_이후부터_시작한다(
        int firstState, int middleState, int lastState, long expectedStart
    ) {
        // given
        var search = new ReverseBoundarySearch(ROUTE, row(4, 5, 0, 80), null);
        var rows = List.of(row(3, 4, lastState, 60), row(2, 4, middleState, 40),
            row(1, 4, firstState, 20), row(0, 3, 2, 0));

        // when
        rows.stream().takeWhile(ignored -> !search.boundaryConfirmed()).forEachOrdered(search::inspect);

        // then
        assertThat(search.boundaryConfirmed()).isTrue();
        assertThat(search.replayStart().id()).isEqualTo(expectedStart);
    }

    @ParameterizedTest
    @CsvSource({"600,1", "601,2"})
    void 같은_회차지의_두_출발_사이가_10분이면_연결하고_10분_1초면_연결하지_않는다(int gap, long expectedStart) {
        // given
        var search = new ReverseBoundarySearch(ROUTE, row(3, 5, 0, gap + 20), null);
        var rows = List.of(row(2, 4, 2, gap), row(1, 4, 2, 0));

        // when
        rows.stream().takeWhile(ignored -> !search.boundaryConfirmed()).forEachOrdered(search::inspect);

        // then
        assertThat(search.replayStart().id()).isEqualTo(expectedStart);
    }

    @Test
    void 회차지에_도착한_관측이_문제이면_그_이전_방향의_관측을_계속_탐색한다() {
        // given
        var search = new ReverseBoundarySearch(ROUTE, row(3, 4, 1, 60), null);
        var rows = List.of(row(2, 3, 2, 40), row(1, 1, 2, 20));

        // when
        rows.stream().forEachOrdered(search::inspect);

        // then
        assertThat(search.replayStart().id()).isEqualTo(1);
    }

    @Test
    void 회차지_출발_후_상태가_바뀐_관측이_문제여도_앞선_첫_출발을_찾는다() {
        // given
        var search = new ReverseBoundarySearch(ROUTE, row(3, 4, 1, 60), null);

        // when
        List.of(row(2, 4, 0, 40), row(1, 4, 2, 20), row(0, 3, 2, 0))
            .stream().takeWhile(ignored -> !search.boundaryConfirmed()).forEachOrdered(search::inspect);

        // then
        assertThat(search.boundaryConfirmed()).isTrue();
        assertThat(search.replayStart().id()).isEqualTo(1);
    }

    @Test
    void 경계가_확정되면_스트림이_더_오래된_관측을_판정하지_않는다() {
        // given
        var search = new ReverseBoundarySearch(ROUTE, row(3, 5, 0, 60), null);
        var rows = List.of(row(2, 4, 2, 40), row(1, 3, 2, 20), row(0, 1, 2, 0));

        // when
        List<Long> inspected = new ArrayList<>();
        rows.stream().takeWhile(ignored -> !search.boundaryConfirmed()).forEachOrdered(row -> {
            inspected.add(row.id());
            search.inspect(row);
        });

        // then
        assertThat(inspected).containsExactly(2L, 1L);
        assertThat(search.replayStart().id()).isEqualTo(2);
    }

    private static Observation row(long id, int stop, int state, int seconds) {
        return new Observation(id, "bus", AT.plusSeconds(seconds), stop, state, 44);
    }
}
