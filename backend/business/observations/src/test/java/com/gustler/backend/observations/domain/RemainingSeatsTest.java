package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.observations.domain.RemainingSeats.Known;
import com.gustler.backend.observations.domain.RemainingSeats.Unknown;
import org.junit.jupiter.api.Test;

class RemainingSeatsTest {
    @Test
    void 아는_잔여석은_음수일_수_없다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new Known(-1));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석을_모르는_경우에도_사유는_반드시_있다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new Unknown(null));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(NullPointerException.class);
    }
}
