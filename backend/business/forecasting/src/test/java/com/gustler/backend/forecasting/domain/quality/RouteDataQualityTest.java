package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

class RouteDataQualityTest {
    @Test
    void 품질_버전이_최댓값이면_변경을_거절하고_기존_버전을_유지한다() {
        // when
        RouteDataQuality quality = new RouteDataQuality(1, Long.MAX_VALUE);


        // then

        // when
        Throwable phaseFailure1 = catchThrowable(quality::changeEligibility);

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(ArithmeticException.class);
        assertThat(quality.revision()).isEqualTo(Long.MAX_VALUE);
    }
}
