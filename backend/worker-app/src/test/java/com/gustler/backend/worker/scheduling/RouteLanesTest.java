package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteLanesTest {
    @Test
    void 같은_노선의_일은_한_줄에서_넣은_순서대로_하나씩_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 1000, null);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlapped = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(200);

        try {
            // when
            for (int index = 0; index < 200; index++) {
                final int number = index;
                lanes.submit("234001739", () -> {
                    if (running.incrementAndGet() > 1) {
                        overlapped.incrementAndGet();
                    }
                    order.add(number);
                    running.decrementAndGet();
                    done.countDown();
                });
            }

            // then
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(overlapped).hasValue(0);
            assertThat(order).isSorted().hasSize(200);
        } finally {
            lanes.destroy();
        }
    }

    @Test
    void 다른_줄에_배정된_노선은_동시에_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 10, null);
        String first = "first";
        String second = keyOnOtherLane(lanes, first);
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            // when
            for (String key : List.of(first, second)) {
                lanes.submit(key, () -> {
                    bothRunning.countDown();
                    await(release);
                });
            }

            // then
            assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            lanes.destroy();
        }
    }

    @Test
    void 줄_수가_1이든_2이든_노선마다_실행_순서가_같다() throws Exception {
        // given
        List<String> keys = List.of("234001739", "204000057", "234000050", "200000314", "227000038");

        // when
        Map<String, List<Integer>> one = runAll(new RouteLanes(1, 1000, null), keys);
        Map<String, List<Integer>> two = runAll(new RouteLanes(2, 1000, null), keys);

        // then
        assertThat(two).isEqualTo(one);
        assertThat(one.values()).allSatisfy(order -> assertThat(order).isSorted().hasSize(50));
    }

    @Test
    void 줄이_가득_차면_넣지_않고_거짓을_돌려주며_일이_실패해도_다음_일을_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(1, 1, new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch after = new CountDownLatch(1);

        try {
            lanes.submit("key", () -> {
                started.countDown();
                await(release);
                throw new IllegalStateException("실패 흉내");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(lanes.submit("key", after::countDown)).isTrue();

            // when
            final boolean accepted = lanes.submit("key", () -> { });

            // then
            assertThat(accepted).isFalse();
            release.countDown();
            assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            lanes.destroy();
        }
    }

    @Test
    void 줄을_비우면_실행_중인_일과_기다리는_일을_모두_끝낸_뒤_돌아오고_새_일은_받지_않는다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 100, null);
        String first = "first";
        String second = keyOnOtherLane(lanes, first);
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        try {
            for (String key : List.of(first, second)) {
                lanes.submit(key, () -> {
                    started.countDown();
                    pause(200);
                    finished.incrementAndGet();
                });
                for (int index = 0; index < 20; index++) {
                    lanes.submit(key, finished::incrementAndGet);
                }
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            QueueDrain drained = lanes.drain(Duration.ofSeconds(10));
            final int finishedAtReturn = finished.get();
            final boolean accepted = lanes.submit(first, finished::incrementAndGet);

            // then
            assertSoftly(softly -> {
                softly.assertThat(drained).isEqualTo(QueueDrain.COMPLETE);
                softly.assertThat(finishedAtReturn).isEqualTo(42);
                softly.assertThat(accepted).isFalse();
                softly.assertThat(lanes.queued()).isZero();
            });
        } finally {
            lanes.destroy();
        }
    }

    @Test
    void 한도_안에_끝나지_않으면_기다리던_일을_버리고_실행_중인_일을_중단시킨_뒤_제시간에_돌아온다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(1, 10, null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger later = new AtomicInteger();
        try {
            lanes.submit("key", () -> {
                started.countDown();
                await(never);
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            for (int index = 0; index < 3; index++) {
                lanes.submit("key", later::incrementAndGet);
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            final long before = System.nanoTime();
            QueueDrain drained = lanes.drain(Duration.ofMillis(200));
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            pause(200);

            // then
            assertSoftly(softly -> {
                softly.assertThat(drained).isEqualTo(new QueueDrain(3, 1));
                softly.assertThat(elapsedMillis).isBetween(150L, 2_000L);
                softly.assertThat(interrupted).isTrue();
                softly.assertThat(later).hasValue(0);
            });
        } finally {
            never.countDown();
            lanes.destroy();
        }
    }

    private static Map<String, List<Integer>> runAll(RouteLanes lanes, List<String> keys) throws InterruptedException {
        Map<String, List<Integer>> orders = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(keys.size() * 50);
        try {
            for (int index = 0; index < 50; index++) {
                for (String key : keys) {
                    final int number = index;
                    lanes.submit(key, () -> {
                        orders.computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                            .add(number);
                        done.countDown();
                    });
                }
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            lanes.destroy();
        }
        Map<String, List<Integer>> copied = new HashMap<>();
        orders.forEach((key, order) -> copied.put(key, List.copyOf(order)));
        return copied;
    }

    private static String keyOnOtherLane(RouteLanes lanes, String key) {
        for (int index = 0; ; index++) {
            String candidate = "route-" + index;
            if (lanes.laneOf(candidate) != lanes.laneOf(key)) {
                return candidate;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
