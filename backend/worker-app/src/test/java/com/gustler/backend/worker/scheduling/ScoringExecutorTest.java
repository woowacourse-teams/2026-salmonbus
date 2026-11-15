package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
        assertSoftly(softly -> {
            softly.assertThat(queued).isTrue();
            softly.assertThat(rejected).isFalse();
            softly.assertThat(meters.get("salmonbus.pipeline.scoring.queue").gauge().value()).isEqualTo(1);
        });
        release.countDown();
    }

    @Test
    void 줄을_비우면_받은_일을_순서대로_모두_끝낸_뒤_돌아오고_새_일은_받지_않는다() throws InterruptedException {
        // given
        executor = new ScoringExecutor(100, meters);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            await(release);
            order.add(0);
        });
        for (int index = 1; index <= 30; index++) {
            final int id = index;
            executor.submit(() -> order.add(id));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        // when
        QueueDrain drained = executor.drain(Duration.ofSeconds(10));
        List<Integer> orderAtReturn = List.copyOf(order);
        final boolean accepted = executor.submit(() -> order.add(99));

        // then
        assertSoftly(softly -> {
            softly.assertThat(drained).isEqualTo(QueueDrain.COMPLETE);
            softly.assertThat(orderAtReturn).hasSize(31).isSorted();
            softly.assertThat(accepted).isFalse();
        });
    }

    @Test
    void 한도_안에_끝나지_않으면_기다리던_일을_버리고_버린_수를_돌려준다() throws InterruptedException {
        // given
        executor = new ScoringExecutor(100, meters);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        executor.submit(() -> {
            started.countDown();
            await(never);
        });
        for (int index = 1; index <= 5; index++) {
            final int id = index;
            executor.submit(() -> ran.add(id));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        final long before = System.nanoTime();
        QueueDrain drained = executor.drain(Duration.ZERO);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(drained).isEqualTo(new QueueDrain(5, 1));
            softly.assertThat(elapsedMillis).isLessThan(1_000);
            softly.assertThat(ran).isEmpty();
        });
        never.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
