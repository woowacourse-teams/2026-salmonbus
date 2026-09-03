package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ForecastPropertiesTest {

    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final Duration SETTLEMENT_INTERVAL = Duration.ofSeconds(60);
    private static final Duration STATISTICS_INTERVAL = Duration.ofHours(6);
    private static final int BATCH_LIMIT = 20;
    private static final int PENDING_LIMIT = 3000;
    private static final int ARRIVAL_LIMIT = 400;

    private static final Duration LONGEST_ALLOWED_STALENESS = Duration.ofHours(1);
    private static final Duration TOO_LONG_STALENESS = LONGEST_ALLOWED_STALENESS.plusSeconds(1);

    @Test
    void 신선도_창이_한_시간을_넘으면_기동을_막는다() {
        // when
        // then 창이 넓어지면 큐가 오래된 판부터 채워서 방금 들어온 판이 회차에 못 들어간다
        assertThatThrownBy(() -> propertiesWith(TOO_LONG_STALENESS))
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
        // then
        assertThatThrownBy(() -> propertiesWith(Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forecast.staleness 는 0보다 길어야 한다: PT0S");
    }

    private static ForecastProperties propertiesWith(
        Duration staleness
    ) {
        return new ForecastProperties(
            true, INTERVAL, SETTLEMENT_INTERVAL, STATISTICS_INTERVAL,
            staleness, BATCH_LIMIT, PENDING_LIMIT, ARRIVAL_LIMIT);
    }
}
