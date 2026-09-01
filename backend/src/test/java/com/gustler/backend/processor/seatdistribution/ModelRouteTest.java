package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModelRouteTest {

    @ParameterizedTest
    @CsvSource({"234000050, 1650", "204000057, 3330"})
    void GBIS_노선_id_를_계수_묶음이_쓰는_노선_이름으로_옮긴다(
        String upstreamRouteId,
        String expected
    ) {
        // when
        String actual = ModelRoute.of(upstreamRouteId);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 목록에_없는_GBIS_노선은_옮기지_않는다() {
        // when & then
        assertThat(catchThrowable(() -> ModelRoute.of("999999999")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 목록에_없는_GBIS_노선은_예외를_안_쓰고도_가려낼_수_있다() {
        // when
        final boolean actual = ModelRoute.covers("999999999");

        // then
        assertThat(actual).isFalse();
    }
}
