package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ShadowForecastExecutorTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ShadowForecastExecutor executor;

    @AfterEach
    void close() {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    void 넣은_순서대로_전용_스레드_하나에서_실행한다() throws InterruptedException {
        // given
        executor = new ShadowForecastExecutor(10, meters);
        List<String> threads = new CopyOnWriteArrayList<>();
        List<Long> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(5);

        // when
        for (long batch = 1; batch <= 5; batch++) {
            final long id = batch;
            executor.submit(new Task(id, () -> {
                threads.add(Thread.currentThread().getName());
                order.add(id);
                done.countDown();
            }));
        }

        // then
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(threads).allSatisfy(name -> assertThat(name).startsWith(ShadowForecastExecutor.THREAD_PREFIX))
            .containsOnly(threads.getFirst());
    }

    @Test
    void 줄이_가득_차면_기다리지_않고_새_일을_버리며_버린_일의_상태를_버리게_한다() throws InterruptedException {
        // given
        executor = new ShadowForecastExecutor(2, meters);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(new Task(1, () -> {
            started.countDown();
            await(release);
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Task second = new Task(2, () -> { });
        Task third = new Task(3, () -> { });
        Task fourth = new Task(4, () -> { });

        // when
        final long before = System.nanoTime();
        executor.submit(second);
        executor.submit(third);
        executor.submit(fourth);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isLessThan(1_000);
            softly.assertThat(fourth.discarded.get()).isOne();
            softly.assertThat(second.discarded.get() + third.discarded.get()).isZero();
            softly.assertThat(executor.queued()).isEqualTo(2);
            softly.assertThat(meters.get("salmonbus.pipeline.shadow.queue").gauge().value()).isEqualTo(2);
        });
        release.countDown();
    }

    @Test
    void 일_하나가_예외를_던져도_다음_일을_계속_실행한다() throws InterruptedException {
        // given
        executor = new ShadowForecastExecutor(10, null);
        CountDownLatch next = new CountDownLatch(1);

        // when
        executor.submit(new Task(1, () -> {
            throw new IllegalStateException("그림자 실패");
        }));
        executor.submit(new Task(2, next::countDown));

        // then
        assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Task implements ShadowForecastTask {
        private final long batch;
        private final Runnable body;
        private final AtomicInteger discarded = new AtomicInteger();

        private Task(final long batch, Runnable body) {
            this.batch = batch;
            this.body = body;
        }

        @Override
        public long observationBatchId() {
            return batch;
        }

        @Override
        public String route() {
            return "3330";
        }

        @Override
        public void run() {
            body.run();
        }

        @Override
        public void discard() {
            discarded.incrementAndGet();
        }
    }
}
