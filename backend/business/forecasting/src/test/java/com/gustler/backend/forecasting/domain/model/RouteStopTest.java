package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

class RouteStopTest {
    @Test
    void 정류장_순번은_1번부터다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new RouteStop(1L, 0, "204000206", true));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }
}
