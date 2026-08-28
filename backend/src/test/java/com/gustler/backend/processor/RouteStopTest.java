package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RouteStopTest {

    @Test
    void 정류장_순번은_1번부터다() {
        // when & then
        assertThatThrownBy(() -> new RouteStop(1L, 0, "204000206"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
