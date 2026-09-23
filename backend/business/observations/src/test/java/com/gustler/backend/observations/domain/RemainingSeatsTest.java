package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.observations.domain.RemainingSeats.Known;
import com.gustler.backend.observations.domain.RemainingSeats.Unknown;
import org.junit.jupiter.api.Test;

class RemainingSeatsTest {
    @Test
    void 아는_잔여석은_음수일_수_없다() {
        // when & then
        assertThatThrownBy(() -> new Known(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석을_모르는_경우에도_사유는_반드시_있다() {
        // when & then
        assertThatThrownBy(() -> new Unknown(null))
            .isInstanceOf(NullPointerException.class);
    }
}
