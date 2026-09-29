package com.gustler.backend.api.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteStatusTest {

    @ParameterizedTest
    @CsvSource({
        "true, true, FORECAST_READY",
        "true, false, PREPARING",
        "false, true, PREPARING",
        "false, false, PREPARING"
    })
    void 활성_모델이_있고_현재_판본에_발행된_예보가_있을_때만_예보_준비_상태다(
        final boolean activeModelExists,
        final boolean forecastPublished,
        RouteStatus expected
    ) {
        // when
        final RouteStatus actual = RouteStatus.from(activeModelExists, forecastPublished);

        // then
        assertThat(actual).isEqualTo(expected);
    }
}
