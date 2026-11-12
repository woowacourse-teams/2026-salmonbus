package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SettlementDelayTest {
    @Test
    void 작업이_남으면_실행_시간에_비례한_간격으로_이어간다() {
        var delay = new SettlementDelay(Duration.ofMinutes(1), Duration.ofMillis(100), 0.25);
        delay.succeeded(true, Duration.ofMillis(20));
        assertThat(delay.current()).isEqualTo(Duration.ofMillis(100));
        delay.succeeded(true, Duration.ofSeconds(4));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(1));
        delay.succeeded(true, Duration.ofSeconds(40));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 대기와_실패_후_복구를_구분한다() {
        var delay = new SettlementDelay(Duration.ofMinutes(1), Duration.ofMillis(100), 0.25);
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

    @Test
    void 쉬는_비율과_최소_간격을_올리면_진행이_이어져도_점유율이_낮아진다() {
        var delay = new SettlementDelay(Duration.ofMinutes(1), Duration.ofSeconds(2), 3);
        delay.succeeded(true, Duration.ofMillis(100));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(2));
        delay.succeeded(true, Duration.ofSeconds(4));
        assertThat(delay.current()).isEqualTo(Duration.ofSeconds(12));
        delay.succeeded(true, Duration.ofSeconds(30));
        assertThat(delay.current()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void 유휴_간격을_넘는_최소_간격과_범위_밖_쉬는_비율은_기동을_막는다() {
        assertThatThrownBy(() -> new SettlementDelay(Duration.ofSeconds(10), Duration.ofSeconds(11), 0.25))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDelay(Duration.ofSeconds(10), Duration.ofMillis(-1), 0.25))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDelay(Duration.ofSeconds(10), Duration.ZERO, -0.1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDelay(Duration.ofSeconds(10), Duration.ZERO, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
