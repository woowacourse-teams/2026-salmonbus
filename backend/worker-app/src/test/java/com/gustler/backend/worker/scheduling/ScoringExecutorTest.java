package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ScoringExecutorTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ScoringExecutor executor;

    @AfterEach
    void close() {
        if (executor != null) {
            executor.stop();
        }
    }

    @Test
    void 넣은_순서대로_채점_전용_스레드_하나에서_실행하고_실패한_일_뒤에도_이어_간다() throws InterruptedException {
        // given
        executor = new ScoringExecutor(10, WAIT, meters);
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
        executor = new ScoringExecutor(1, WAIT, meters);
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
    void 닫으면_새_일을_받지_않고_기다리던_일은_버리며_돌던_일에_중단을_요청하고_끝나기를_기다린다(CapturedOutput output)
        throws InterruptedException {
        // given
        executor = new ScoringExecutor(100, WAIT, meters);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        executor.submit(() -> {
            started.countDown();
            untilInterrupted();
            interrupted.set(Thread.currentThread().isInterrupted());
            finished.set(true);
        });
        for (int index = 1; index <= 30; index++) {
            final int id = index;
            executor.submit(() -> ran.add(id));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        executor.stop();
        final boolean finishedAtReturn = finished.get();
        final boolean accepted = executor.submit(() -> ran.add(99));

        // then
        assertSoftly(softly -> {
            softly.assertThat(interrupted).as("돌던 일은 중단 요청을 받았다").isTrue();
            softly.assertThat(finishedAtReturn).as("돌던 일은 닫기가 돌아오기 전에 끝났다").isTrue();
            softly.assertThat(ran).as("기다리던 일은 실행하지 않는다").isEmpty();
            softly.assertThat(accepted).isFalse();
            softly.assertThat(executor.isRunning()).isFalse();
            softly.assertThat(output.getOut()).contains("event=pipeline_scoring_stopped discarded=30")
                .doesNotContain("event=pipeline_scoring_stop_timeout");
        });
    }

    @Test
    void 돌던_일이_한도_안에_끝나지_않으면_경고를_남기고_한도까지만_기다린다(CapturedOutput output) throws InterruptedException {
        // given
        executor = new ScoringExecutor(100, Duration.ofMillis(200), meters);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        executor.submit(() -> {
            started.countDown();
            ignoringInterrupts(release);
        });
        for (int index = 1; index <= 5; index++) {
            final int id = index;
            executor.submit(() -> ran.add(id));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        final long before = System.nanoTime();
        executor.stop();
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isGreaterThanOrEqualTo(150L);
            softly.assertThat(ran).isEmpty();
            softly.assertThat(output.getOut()).contains("event=pipeline_scoring_stop_timeout discarded=5 waitMs=200");
        });
        release.countDown();
    }

    @Test
    void 컨텍스트가_닫힐_때_예보_실행_줄_바로_다음에_멈추고_일시_정지에는_참여하지_않는다() {
        // when
        executor = new ScoringExecutor(1, WAIT, null);
        RouteLanes lanes = new RouteLanes(1, 1, WAIT, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(executor.getPhase()).as("단계가 큰 것부터 멈춘다").isEqualTo(lanes.getPhase() - 1);
            softly.assertThat(executor.isRunning()).as("닫기 전에는 멈출 대상이다").isTrue();
            softly.assertThat(executor.isPauseable()).isFalse();
            softly.assertThat(executor.isAutoStartup()).isTrue();
        });
    }

    /** 중단 요청을 받을 때까지 스레드를 붙잡는다. 한도 없이 기다린다. */
    private static void untilInterrupted() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 풀어 줄 때까지 스레드를 붙잡는다. 중단 요청으로는 풀리지 않는다. */
    private static void ignoringInterrupts(CountDownLatch release) {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (release.getCount() > 0 && System.nanoTime() < until) {
            Thread.interrupted();
            await(release, 10);
        }
    }

    private static void await(CountDownLatch latch, final long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
