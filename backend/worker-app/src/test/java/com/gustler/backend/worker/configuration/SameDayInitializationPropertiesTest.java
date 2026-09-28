package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SameDayInitializationPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void 영_이하의_초기화_주기와_제한시간은_거부한다(long millis) {
        Duration valid = Duration.ofSeconds(1);
        Duration invalid = Duration.ofMillis(millis);
        assertThatThrownBy(() -> new SameDayInitializationProperties(invalid, valid, valid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameDayInitializationProperties(valid, invalid, valid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameDayInitializationProperties(valid, valid, invalid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SameDayInitializationProperties(valid, valid, valid, invalid))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
