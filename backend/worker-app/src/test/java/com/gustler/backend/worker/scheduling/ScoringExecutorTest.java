package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScoringExecutorTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ScoringExecutor executor;

    @AfterEach
    void close() {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    void 넣은_순서대로_채점_전용_스레드_하나에서_실행하고_실패한_일_뒤에도_이어_간다() throws InterruptedException {
        // given
        executor = new ScoringExecutor(10, meters);
        List<String> threads = new CopyOnWriteArrayList<>();
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(4);

        // when
        for (int index = 1; index <= 5; index++) {
            final int id = index;
            executor.submit(() -> {
                if (id == 3) {
                    throw new IllegalStateException("실패 흉내");
                }
                threads.add(Thread.currentThread().getName());
                order.add(id);
                done.countDown();
            });
        }

        // then
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 4, 5);
        assertThat(threads).allSatisfy(name -> assertThat(name).startsWith(ScoringExecutor.THREAD_PREFIX))
            .containsOnly(threads.getFirst());
    }

    @Test
    void 줄이_가득_차면_기다리지_않고_넣지_못했다고_알리며_줄_길이를_지표로_낸다() throws InterruptedException {
        // given
        executor = new ScoringExecutor(1, meters);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            await(release);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final boolean queued = executor.submit(() -> { });

        // when
        final boolean rejected = executor.submit(() -> { });

        // then
        assertThat(queued).isTrue();
        assertThat(rejected).isFalse();
        assertThat(meters.get("salmonbus.pipeline.scoring.queue").gauge().value()).isEqualTo(1);
        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
