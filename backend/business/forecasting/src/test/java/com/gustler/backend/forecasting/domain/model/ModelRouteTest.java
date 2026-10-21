package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModelRouteTest {
    @ParameterizedTest
    @CsvSource({
        "234000050, 1650", "204000057, 3330",
        "204000070, 9007", "234000886, 9300", "233000392, 6011",
        "227000038, 3000", "228000184, 5600", "234000051, 3500",
        "204000081, 1151", "234000002, 9000", "234000313, 1500-2",
        "234000878, 8100", "234000882, 1150", "204000082, G8110",
        "234001739, 6801", "233000266, 6003", "204000170, 3100",
        "234000879, 1101", "228000393, 7007-1", "200000314, 4000"
    })
    void GBIS_노선_id_를_계수_묶음이_쓰는_노선_이름으로_옮긴다(
        String sourceRouteId,
        String expected
    ) {
        // when
        String actual = ModelRoute.of(sourceRouteId);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 목록에_없는_GBIS_노선은_옮기지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> ModelRoute.of("999999999"));

        // then
        assertThat(caughtFailure)
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
