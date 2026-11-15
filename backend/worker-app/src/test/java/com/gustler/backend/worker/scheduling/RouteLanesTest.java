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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(OutputCaptureExtension.class)
class RouteLanesTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void 같은_노선의_일은_한_줄에서_넣은_순서대로_하나씩_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 1000, WAIT, null);
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
            lanes.stop();
        }
    }

    @Test
    void 다른_줄에_배정된_노선은_동시에_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 10, WAIT, null);
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
            lanes.stop();
        }
    }

    @Test
    void 줄_수가_1이든_2이든_노선마다_실행_순서가_같다() throws Exception {
        // given
        List<String> keys = List.of("234001739", "204000057", "234000050", "200000314", "227000038");

        // when
        Map<String, List<Integer>> one = runAll(new RouteLanes(1, 1000, WAIT, null), keys);
        Map<String, List<Integer>> two = runAll(new RouteLanes(2, 1000, WAIT, null), keys);

        // then
        assertThat(two).isEqualTo(one);
        assertThat(one.values()).allSatisfy(order -> assertThat(order).isSorted().hasSize(50));
    }

    @Test
    void 줄이_가득_차면_넣지_않고_거짓을_돌려주며_일이_실패해도_다음_일을_실행한다() throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(1, 1, WAIT, new SimpleMeterRegistry());
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
            lanes.stop();
        }
    }

    @Test
    void 닫으면_새_일을_받지_않고_기다리던_일은_버리며_돌던_일은_중단시키지_않고_끝나기를_기다린다(CapturedOutput output)
        throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(2, 100, WAIT, null);
        String first = "first";
        String second = keyOnOtherLane(lanes, first);
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger sawClosing = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        for (String key : List.of(first, second)) {
            lanes.submit(key, () -> {
                started.countDown();
                pause(200);
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.incrementAndGet();
                }
                if (lanes.closing()) {
                    sawClosing.incrementAndGet();
                }
                finished.incrementAndGet();
            });
            for (int index = 0; index < 20; index++) {
                lanes.submit(key, later::incrementAndGet);
            }
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final boolean closingBeforeStop = lanes.closing();

        // when
        lanes.stop();
        final int finishedAtReturn = finished.get();
        final boolean accepted = lanes.submit(first, later::incrementAndGet);

        // then
        assertSoftly(softly -> {
            softly.assertThat(finishedAtReturn).as("돌던 일은 닫기가 돌아오기 전에 끝났다").isEqualTo(2);
            softly.assertThat(closingBeforeStop).isFalse();
            softly.assertThat(sawClosing).as("돌던 일은 줄이 닫히기 시작한 것을 안다").hasValue(2);
            softly.assertThat(interrupted).hasValue(0);
            softly.assertThat(later).as("기다리던 일은 실행하지 않는다").hasValue(0);
            softly.assertThat(accepted).isFalse();
            softly.assertThat(lanes.queued()).isZero();
            softly.assertThat(lanes.isRunning()).isFalse();
            softly.assertThat(output.getOut()).contains("event=pipeline_forecast_stopped discarded=40")
                .doesNotContain("event=pipeline_forecast_stop_timeout");
        });
    }

    @Test
    void 돌던_일이_한도_안에_끝나지_않으면_경고를_남기고_제시간에_돌아온다(CapturedOutput output) throws Exception {
        // given
        RouteLanes lanes = new RouteLanes(1, 10, Duration.ofMillis(200), null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger later = new AtomicInteger();
        try {
            lanes.submit("key", () -> {
                started.countDown();
                await(never);
            });
            for (int index = 0; index < 3; index++) {
                lanes.submit("key", later::incrementAndGet);
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            final long before = System.nanoTime();
            lanes.stop();
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

            // then
            assertSoftly(softly -> {
                softly.assertThat(elapsedMillis).isBetween(150L, 2_000L);
                softly.assertThat(later).hasValue(0);
                softly.assertThat(output.getOut())
                    .contains("event=pipeline_forecast_stop_timeout discarded=3 running=1 waitMs=200");
            });
        } finally {
            never.countDown();
        }
    }

    @Test
    void 컨텍스트가_닫힐_때_스케줄러가_돌던_회차를_끝낸_바로_다음에_멈추고_일시_정지에는_참여하지_않는다() {
        // when
        RouteLanes lanes = new RouteLanes(1, 1, WAIT, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(lanes.getPhase()).as("단계가 큰 것부터 멈춘다")
                .isEqualTo(new ThreadPoolTaskScheduler().getPhase() - 1);
            softly.assertThat(lanes.isRunning()).as("닫기 전에는 멈출 대상이다").isTrue();
            softly.assertThat(lanes.isPauseable()).isFalse();
            softly.assertThat(lanes.isAutoStartup()).isTrue();
        });
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
            lanes.stop();
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
