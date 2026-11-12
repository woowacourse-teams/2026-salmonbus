package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class SettlementRouteRunnerTest {
    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final SettlementRouteRunner runner = new SettlementRouteRunner(clock, "settlement_route");

    @Test
    void 실패_노선의_재시도를_기다리는_동안_다른_노선은_계속_처리한다() {
        when(clock.instant()).thenReturn(START);
        List<Long> completed = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        LongConsumer settle = version -> {
            if (version == 1) {
                failures.incrementAndGet();
                throw new IllegalStateException("실패 노선");
            }
            completed.add(version);
        };

        runner.run(List.of(1L, 2L), settle);
        runner.run(List.of(1L, 2L), settle);

        assertThat(failures.get()).isOne();
        assertThat(completed).containsExactly(2L, 2L);
    }

    @Test
    void 연속_실패는_재시도_간격을_늘리고_최대_간격을_넘기지_않는다() {
        AtomicInteger attempts = new AtomicInteger();
        LongConsumer fail = version -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("반복 실패");
        };
        Instant now = START;
        when(clock.instant()).thenReturn(now);
        runner.run(List.of(1L), fail);
        int expected = 1;
        for (int seconds : List.of(2, 4, 8, 16, 32, 60, 60)) {
            when(clock.instant()).thenReturn(now.plusSeconds(seconds - 1));
            runner.run(List.of(1L), fail);
            assertThat(attempts.get()).isEqualTo(expected);
            now = now.plusSeconds(seconds);
            when(clock.instant()).thenReturn(now);
            runner.run(List.of(1L), fail);
            assertThat(attempts.get()).isEqualTo(++expected);
        }
    }

    @Test
    void 성공_후_다시_실패하면_첫_재시도_간격으로_돌아간다() {
        AtomicInteger attempts = new AtomicInteger();
        LongConsumer settle = version -> {
            if (attempts.incrementAndGet() != 2) {
                throw new IllegalStateException("두 번째 시도만 성공");
            }
        };
        when(clock.instant()).thenReturn(START);
        runner.run(List.of(1L), settle);
        when(clock.instant()).thenReturn(START.plusSeconds(2));
        runner.run(List.of(1L), settle);
        runner.run(List.of(1L), settle);
        when(clock.instant()).thenReturn(START.plusSeconds(4));
        runner.run(List.of(1L), settle);

        assertThat(attempts.get()).isEqualTo(4);
    }
    @Test
    void 예산으로_건너뛴_노선의_재시도_상태를_보존한다() {
        when(clock.instant()).thenReturn(START);
        AtomicInteger failures = new AtomicInteger();
        LongConsumer settle = route -> {
            if (route == 2) {
                failures.incrementAndGet();
                throw new IllegalStateException("retry");
            }
        };
        runner.run(List.of(1L, 2L), settle);
        AtomicInteger admitted = new AtomicInteger();
        runner.run(List.of(1L, 2L), settle, () -> admitted.getAndIncrement() < 1);
        runner.run(List.of(1L, 2L), settle);
        assertThat(failures.get()).isOne();
    }
}
