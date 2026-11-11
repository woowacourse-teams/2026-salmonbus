package com.gustler.backend.arrivals.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArrivalRateLimitDrainTest {
    @Test
    void 키_하나_기본_한도의_한_시간은_424회이고_끝_시각의_토큰은_세지_않는다() {
        assertThat(drain(10_000, 8, Duration.ofHours(1))).isEqualTo(424);
        assertThat(drain(86_400, 8, Duration.ofHours(1))).isEqualTo(3607);
    }

    private static int drain(int dailyLimit, int burst, Duration window) {
        ArrivalRateLimit limit = new ArrivalRateLimit(dailyLimit / 86_400.0, burst, 0);
        long now = 0;
        long end = window.toNanos();
        int calls = 0;
        while (now < end) {
            Duration wait = limit.acquire(now);
            if (wait.isZero()) {
                calls++;
                continue;
            }
            now += wait.toNanos();
        }
        return calls;
    }
}
