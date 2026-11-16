package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ForecastPropertiesTest {
    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final int BATCH_LIMIT = 20;
    private static final int LANES = 2;

    private static final Duration LONGEST_ALLOWED_STALENESS = ForecastProperties.MAX_STALENESS;
    private static final Duration TOO_LONG_STALENESS = LONGEST_ALLOWED_STALENESS.plusSeconds(1);

    @Test
    void 신선도_창이_한_시간을_넘으면_기동을_막는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> propertiesWith(TOO_LONG_STALENESS));

        // then
        // 창이 넓어지면 큐가 오래된 관측부터 채워져 방금 들어온 관측이 회차에 포함되지 않는다.
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forecast.staleness 는 PT1H 이하여야 한다: PT1H1S");
    }

    @Test
    void 신선도_창이_한_시간이면_받는다() {
        // when
        ForecastProperties actual = propertiesWith(LONGEST_ALLOWED_STALENESS);

        // then
        assertThat(actual.staleness()).isEqualTo(LONGEST_ALLOWED_STALENESS);
    }

    @Test
    void 신선도_창이_0이면_기동을_막는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> propertiesWith(Duration.ZERO));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forecast.staleness 는 0보다 길어야 한다: PT0S");
    }

    @Test
    void 노선별_실행_줄_수가_1보다_작으면_기동을_막는다() {
        // when
        Throwable caughtFailure = catchThrowable(
            () -> new ForecastProperties(true, INTERVAL, LONGEST_ALLOWED_STALENESS, BATCH_LIMIT, 0));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forecast.lanes 는 1 이상이어야 한다: 0");
    }

    private static ForecastProperties propertiesWith(
        Duration staleness
    ) {
        return new ForecastProperties(true, INTERVAL, staleness, BATCH_LIMIT, LANES);
    }
}
