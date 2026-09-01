package com.gustler.backend.api.board.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForecastHorizonTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 6, 11, 12})
    void 도착_예정인_12개_정류장까지만_보드에_싣는다(
        final int stopCount
    ) {
        assertThat(ForecastHorizon.covers(stopCount)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, -1, 0, 13, 40})
    void 이미_지난_정류장과_13정류장_앞부터는_보드_밖이다(
        final int stopCount
    ) {
        assertThat(ForecastHorizon.covers(stopCount)).isFalse();
    }
}
