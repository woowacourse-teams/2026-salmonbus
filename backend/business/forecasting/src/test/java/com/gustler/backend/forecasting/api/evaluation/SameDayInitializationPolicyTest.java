package com.gustler.backend.forecasting.api.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SameDayInitializationPolicyTest {

    private static final Duration VALID = Duration.ofMillis(1);

    @Test
    void 재시도_간격과_시간_제한이_1밀리초_이상이면_만든다() {
        // when
        SameDayInitializationPolicy actual = new SameDayInitializationPolicy(VALID, VALID, VALID);

        // then
        assertThat(actual.statementTimeout()).isEqualTo(VALID);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 재시도_간격이나_시간_제한이_1밀리초보다_짧으면_거부한다(long millis) {
        Duration invalid = Duration.ofMillis(millis);

        // when & then
        assertThatThrownBy(() -> new SameDayInitializationPolicy(invalid, VALID, VALID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameDayInitializationPolicy(VALID, invalid, VALID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameDayInitializationPolicy(VALID, VALID, invalid))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 값이_없으면_거부한다() {
        // when & then
        assertThatThrownBy(() -> new SameDayInitializationPolicy(null, VALID, VALID))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
