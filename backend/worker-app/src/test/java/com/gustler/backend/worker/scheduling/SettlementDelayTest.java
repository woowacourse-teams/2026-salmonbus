package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SettlementDelayTest {
    @Test
    void 작업이_남으면_실행_시간에_비례한_간격으로_이어간다() {
        var delay = new SettlementDelay(Duration.ofMinutes(1));
        delay.succeeded(true, Duration.ofMillis(20));
        assertThat(delay.current()).isEqualTo(Duration.ofMillis(100));
        delay.succeeded(true, Duration.ofSeconds(4));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(1));
        delay.succeeded(true, Duration.ofSeconds(40));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 대기와_실패_후_복구를_구분한다() {
        var delay = new SettlementDelay(Duration.ofMinutes(1));
        delay.succeeded(false, Duration.ofMillis(1));
        assertThat(delay.current()).isEqualTo(Duration.ofMinutes(1));
        delay.failed();
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(2));
        delay.failed();
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(4));
        for (int i = 0; i < 10; i++) {
            delay.failed();
        }
        assertThat(delay.current()).isEqualTo(Duration.ofMinutes(1));
        delay.succeeded(true, Duration.ofMillis(100));
        delay.failed();
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(2));
    }
}
