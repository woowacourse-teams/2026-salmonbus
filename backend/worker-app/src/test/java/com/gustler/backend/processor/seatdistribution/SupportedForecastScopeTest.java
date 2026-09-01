package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportedForecastScopeTest {

    private static final SupportedForecastScope SCOPE =
        new SupportedForecastScope(List.of("1650", "3330"));

    @ParameterizedTest
    @ValueSource(strings = {"1650", "3330"})
    void 계수_묶음이_담는_노선은_예보_범위_안이다(
        String modelRoute
    ) {
        // when
        final boolean actual = SCOPE.covers(modelRoute, 1);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 계수_묶음이_안_담는_노선은_예보_범위_밖이다() {
        // when
        final boolean actual = SCOPE.covers("9000", 1);

        // then
        assertThat(actual).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 12})
    void 예보_거리가_1정류장_앞부터_12정류장_앞까지면_예보_범위_안이다(
        final int stopsAhead
    ) {
        // when
        final boolean actual = SCOPE.covers("1650", stopsAhead);

        // then
        assertThat(actual).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13})
    void 예보_거리가_0이거나_13정류장_앞이면_예보_범위_밖이다(
        final int stopsAhead
    ) {
        // when
        final boolean actual = SCOPE.covers("1650", stopsAhead);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 노선_순서가_뒤집히면_다른_범위다() {
        // when
        String actual = new SupportedForecastScope(List.of("3330", "1650")).digest();

        // then
        assertThat(actual).isNotEqualTo(SCOPE.digest());
    }
}
