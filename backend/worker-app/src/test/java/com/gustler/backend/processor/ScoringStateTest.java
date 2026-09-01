package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScoringStateTest {

    @ParameterizedTest
    @EnumSource(ScoringState.class)
    void 실제_잔여석을_받아낸_예보만_채점한다(
        ScoringState scoringState
    ) {
        // when
        final boolean actual = scoringState.scorable();

        // then
        assertThat(actual).isEqualTo(scoringState == ScoringState.SETTLED);
    }
}
