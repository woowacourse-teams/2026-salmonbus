package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SameDayInitializationPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void 영_이하의_초기화_주기와_제한시간은_거부한다(final long millis) {
        // when
        Duration valid = Duration.ofSeconds(1);
        Duration invalid = Duration.ofMillis(millis);

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(
            () -> new SameDayInitializationProperties(invalid, valid, valid, valid));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(
            () -> new SameDayInitializationProperties(valid, invalid, valid, valid));

        // then
        assertThat(phaseFailure2)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure3 = catchThrowable(
            () -> new SameDayInitializationProperties(valid, valid, invalid, valid));

        // then
        assertThat(phaseFailure3)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure4 = catchThrowable(
            () -> new SameDayInitializationProperties(valid, valid, valid, invalid));

        // then
        assertThat(phaseFailure4)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }
}
