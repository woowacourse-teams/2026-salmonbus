package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HorizonTest {

    @Test
    void 예보는_다음_정류장부터_시작한다() {
        // when
        final Horizon actual = Horizon.of(1);

        // then
        assertThat(actual.stopsAhead()).isEqualTo(1);
        assertThatThrownBy(() -> Horizon.of(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 도착_예정인_12개_정류장까지만_예보한다() {
        // when
        final Horizon actual = Horizon.of(12);

        // then
        assertThat(actual.stopsAhead()).isEqualTo(12);
        assertThatThrownBy(() -> Horizon.of(13))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
