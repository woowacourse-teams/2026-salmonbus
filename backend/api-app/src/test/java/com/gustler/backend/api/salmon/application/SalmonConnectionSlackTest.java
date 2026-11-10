package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonConnectionSlackTest {
    @Test
    void 버스보다_15초_먼저_서면_연결이다() {
        SalmonConnectionSlack slack = new SalmonConnectionSlack(Duration.ofSeconds(15));
        long bus = Duration.ofMinutes(10).toMillis();

        assertThat(slack.slack()).isEqualTo(Duration.ofSeconds(15));
        assertThat(slack.connected(bus, bus - Duration.ofSeconds(15).toMillis())).isTrue();
        assertThat(slack.connected(bus, bus - Duration.ofSeconds(14).toMillis())).isFalse();
    }

    @Test
    void 여유가_음수면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonConnectionSlack(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
