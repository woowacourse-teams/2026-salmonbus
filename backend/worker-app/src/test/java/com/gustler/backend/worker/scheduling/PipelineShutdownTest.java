package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(OutputCaptureExtension.class)
class PipelineShutdownTest {
    private static final Duration LONG = Duration.ofSeconds(10);
    private static final Duration SHORT_GRACE = Duration.ofMillis(200);

    private final List<ThreadPoolTaskScheduler> schedulers = new ArrayList<>();
    private final List<RouteLanes> lanesToClose = new ArrayList<>();
    private final List<ScoringExecutor> scoringToClose = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void 남은_스레드를_정리한다() {
        release.countDown();
        lanesToClose.forEach(RouteLanes::destroy);
        scoringToClose.forEach(ScoringExecutor::destroy);
        schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
    }

    @Test
    void 수집과_줍기를_멈춘_뒤_예보_줄과_채점_줄을_차례로_비우고_마지막_쓰기를_한다() throws InterruptedException {
        // given
        ThreadPoolTaskScheduler collection = scheduler("collection-");
        ThreadPoolTaskScheduler pickup = scheduler("forecast-");
        RouteLanes lanes = lanes(2, 1000);
        ScoringExecutor scoring = scoring(1000);
        AtomicInteger collected = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger forecasted = new AtomicInteger();
        AtomicInteger scored = new AtomicInteger();
        Runnable round = () -> {
            for (int route = 0; route < 4; route++) {
                final boolean accepted = lanes.submit("route-" + route, () -> {
                    pause(3);
                    forecasted.incrementAndGet();
                    scoring.submit(() -> {
                        pause(1);
                        scored.incrementAndGet();
                    });
                });
                count(accepted, collected, rejected);
                pause(5);
            }
        };
        RecordingCycle cycle = new RecordingCycle();
        AtomicInteger collectedWhenScoringStopped = new AtomicInteger(-1);
        AtomicInteger forecastedWhenScoringStopped = new AtomicInteger(-1);
        AtomicInteger scoredWhenWritten = new AtomicInteger(-1);
        List<Boolean> intakeStopped = new CopyOnWriteArrayList<>();
        cycle.whenStopped = () -> {
            intakeStopped.add(collection.getScheduledThreadPoolExecutor().isTerminated());
            intakeStopped.add(pickup.getScheduledThreadPoolExecutor().isTerminated());
            collectedWhenScoringStopped.set(collected.get());
            forecastedWhenScoringStopped.set(forecasted.get());
        };
        cycle.whenWritten = () -> {
            scoredWhenWritten.set(scored.get());
            return 0;
        };
        PipelineShutdown shutdown = new PipelineShutdown(new PipelineShutdown.Parts(List.of(collection),
            List.of(pickup), List.of(lanes), List.of(scoring), List.of(), List.of(cycle)), LONG);
        shutdown.start();
        collection.schedule(round, context -> Instant.now().plusMillis(10));
        for (int index = 0; index < 5; index++) {
            pickup.execute(round);
        }
        assertThat(waitUntil(() -> collected.get() >= 40)).isTrue();

        // when
        shutdown.stop();
        final int collectedAtReturn = collected.get();
        pause(150);

        // then
        assertSoftly(softly -> {
            softly.assertThat(cycle.calls).containsExactly("stop_accepting", "final_write");
            softly.assertThat(intakeStopped).containsExactly(true, true);
            softly.assertThat(collected).as("멈춘 뒤에는 수집이 더 돌지 않는다").hasValue(collectedAtReturn);
            softly.assertThat(rejected).as("실행 줄을 닫은 뒤에 도착한 배치가 없다").hasValue(0);
            softly.assertThat(collectedWhenScoringStopped).hasValue(collectedAtReturn);
            softly.assertThat(forecastedWhenScoringStopped).as("채점을 멈추기 전에 받은 배치를 모두 예보했다")
                .hasValue(collectedAtReturn);
            softly.assertThat(scoredWhenWritten).as("마지막 쓰기 전에 받은 사건을 모두 채점했다")
                .hasValue(collectedAtReturn);
            softly.assertThat(shutdown.isRunning()).isFalse();
        });
    }

    @Test
    void 스프링이_스케줄러를_멈춰도_남는_다음_수집_예약을_취소한다() {
        // given
        ThreadPoolTaskScheduler springOnly = scheduler("spring-only-");
        ThreadPoolTaskScheduler collection = scheduler("collection-");
        AtomicInteger springOnlyRuns = new AtomicInteger();
        AtomicInteger collectionRuns = new AtomicInteger();
        Instant due = Instant.now().plusMillis(300);
        springOnly.schedule(springOnlyRuns::incrementAndGet, context -> onceAt(context.lastCompletion(), due));
        collection.schedule(collectionRuns::incrementAndGet, context -> onceAt(context.lastCompletion(), due));
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(collection), List.of(), List.of()), LONG);
        shutdown.start();

        // when
        springOnly.initiateShutdown();
        shutdown.stop();
        pause(600);

        // then
        assertSoftly(softly -> {
            softly.assertThat(springOnlyRuns).as("컨텍스트가 닫히기 시작할 때 스프링이 하는 일만으로는 예약된 회차가 실행된다")
                .hasValue(1);
            softly.assertThat(collectionRuns).hasValue(0);
            softly.assertThat(collection.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
        });
    }

    @Test
    void 한도가_지나면_남은_일을_버리고_무엇을_얼마나_버렸는지_한_줄로_남긴다(CapturedOutput output)
        throws InterruptedException {
        // given
        RouteLanes lanes = lanes(1, 10);
        ScoringExecutor scoring = scoring(10);
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger later = new AtomicInteger();
        lanes.submit("key", () -> block(started));
        scoring.submit(() -> block(started));
        for (int index = 0; index < 3; index++) {
            lanes.submit("key", later::incrementAndGet);
        }
        for (int index = 0; index < 2; index++) {
            scoring.submit(later::incrementAndGet);
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        RecordingCycle cycle = new RecordingCycle();
        cycle.whenWritten = () -> 7;
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(lanes), List.of(scoring), cycle),
            Duration.ofMillis(300), SHORT_GRACE);
        shutdown.start();

        // when
        final long before = System.nanoTime();
        shutdown.stop();
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isBetween(250L, 2_000L);
            softly.assertThat(later).as("버린 일은 실행되지 않는다").hasValue(0);
            softly.assertThat(cycle.calls).containsExactly("stop_accepting", "final_write", "final_write");
            softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_abandoned")
                .contains("timeoutMs=300 intakeRunning=0 forecastQueued=3 forecastRunning=1 scoringQueued=2"
                    + " scoringRunning=1 unwritten=7 failedStages=")
                .doesNotContain("event=pipeline_shutdown_drained");
        });
    }

    @Test
    void 수집이_한도의_절반_안에_끝나지_않으면_더_기다리지_않고_줄을_비운다(CapturedOutput output)
        throws InterruptedException {
        // given
        ThreadPoolTaskScheduler collection = scheduler("collection-");
        RouteLanes lanes = lanes(1, 10);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger forecasted = new AtomicInteger();
        collection.execute(() -> block(started));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 5; index++) {
            lanes.submit("key", forecasted::incrementAndGet);
        }
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(collection), List.of(lanes), List.of()),
            Duration.ofSeconds(2));
        shutdown.start();

        // when
        final long before = System.nanoTime();
        shutdown.stop();
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).as("수집 대기는 한도의 절반이다").isBetween(900L, 1_800L);
            softly.assertThat(forecasted).hasValue(5);
            softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_abandoned")
                .contains("intakeRunning=1 forecastQueued=0 forecastRunning=0");
        });
    }

    @Test
    void 한_단계가_예외로_끝나도_다음_단계를_하고_실패한_단계를_남긴다(CapturedOutput output) {
        // given
        ScoringExecutor scoring = scoring(10);
        AtomicInteger scored = new AtomicInteger();
        scoring.submit(scored::incrementAndGet);
        DrainableQueue broken = timeout -> {
            throw new IllegalStateException("비우기 실패 흉내");
        };
        RecordingCycle cycle = new RecordingCycle();
        cycle.whenStopped = () -> {
            throw new IllegalStateException("멈춤 실패 흉내");
        };
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(broken), List.of(scoring), cycle),
            LONG);
        shutdown.start();

        // when
        shutdown.stop();

        // then
        assertSoftly(softly -> {
            softly.assertThat(scored).hasValue(1);
            softly.assertThat(scoring.submit(scored::incrementAndGet)).as("채점 줄은 닫혔다").isFalse();
            softly.assertThat(cycle.calls).containsExactly("stop_accepting", "final_write");
            softly.assertThat(output.getOut())
                .contains("event=pipeline_shutdown_stage_failed stage=forecast exceptionType=IllegalStateException")
                .contains("event=pipeline_shutdown_stage_failed stage=scoring_stop")
                .contains("event=pipeline_shutdown_abandoned")
                .contains("unwritten=0 failedStages=forecast,scoring_stop");
        });
    }

    @Test
    void 마지막_쓰기가_끝나지_않으면_여유_시간만_기다리고_내려간다(CapturedOutput output) {
        // given
        RecordingCycle cycle = new RecordingCycle();
        cycle.whenWritten = () -> {
            await(release);
            return 0;
        };
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(), List.of(), cycle),
            Duration.ZERO, SHORT_GRACE);
        shutdown.start();

        // when
        final long before = System.nanoTime();
        shutdown.stop();
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isBetween(150L, 2_000L);
            softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_abandoned")
                .contains("unwritten=unknown");
        });
    }

    @Test
    void 마지막_쓰기가_실패해_줄이_남으면_한_번_더_쓴다(CapturedOutput output) {
        // given
        RecordingCycle cycle = new RecordingCycle();
        AtomicInteger attempts = new AtomicInteger();
        cycle.whenWritten = () -> remainingAfter(attempts.incrementAndGet());
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(), List.of(), cycle), LONG);
        shutdown.start();

        // when
        shutdown.stop();

        // then
        assertSoftly(softly -> {
            softly.assertThat(attempts).hasValue(2);
            softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_drained")
                .contains("unwritten=0 failedStages=")
                .doesNotContain("event=pipeline_shutdown_abandoned");
        });
    }

    @Test
    void 한_번_멈추면_다시_비우지_않고_시작하지_않았으면_아무것도_하지_않는다() {
        // given
        RecordingCycle started = new RecordingCycle();
        RecordingCycle neverStarted = new RecordingCycle();
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(), List.of(), started), LONG);
        PipelineShutdown idle = new PipelineShutdown(parts(List.of(), List.of(), List.of(), neverStarted), LONG);
        shutdown.start();

        // when
        shutdown.stop();
        shutdown.stop();
        idle.stop();

        // then
        assertSoftly(softly -> {
            softly.assertThat(started.calls).containsExactly("stop_accepting", "final_write");
            softly.assertThat(neverStarted.calls).isEmpty();
        });
    }

    @Test
    void 웹_서버의_요청_마감과_스케줄러_정지보다_먼저_멈추고_일시_정지에는_참여하지_않는다() {
        // when
        PipelineShutdown shutdown = new PipelineShutdown(parts(List.of(), List.of(), List.of()), LONG);

        // then
        assertSoftly(softly -> {
            softly.assertThat(shutdown.getPhase()).isGreaterThan(new ThreadPoolTaskScheduler().getPhase())
                .isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
            softly.assertThat(shutdown.isPauseable()).isFalse();
            softly.assertThat(shutdown.isAutoStartup()).isTrue();
        });
    }

    private static PipelineShutdown.Parts parts(
        List<ThreadPoolTaskScheduler> collection,
        List<DrainableQueue> forecast,
        List<DrainableQueue> scoring,
        ShadowScoringCycle... cycles
    ) {
        return new PipelineShutdown.Parts(collection, List.of(), forecast, scoring, List.of(), List.of(cycles));
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        schedulers.add(scheduler);
        return scheduler;
    }

    private RouteLanes lanes(final int count, final int capacity) {
        RouteLanes lanes = new RouteLanes(count, capacity, null);
        lanesToClose.add(lanes);
        return lanes;
    }

    private ScoringExecutor scoring(final int capacity) {
        ScoringExecutor scoring = new ScoringExecutor(capacity, null);
        scoringToClose.add(scoring);
        return scoring;
    }

    /** 시작을 알리고 시험이 끝날 때까지 스레드를 붙잡는다. 중단 요청으로는 풀리지 않는다. */
    private void block(CountDownLatch started) {
        started.countDown();
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (release.getCount() > 0 && System.nanoTime() < until) {
            pause(10);
            Thread.interrupted();
        }
    }

    private static void count(final boolean accepted, AtomicInteger collected, AtomicInteger rejected) {
        if (accepted) {
            collected.incrementAndGet();
            return;
        }
        rejected.incrementAndGet();
    }

    private static Instant onceAt(Instant lastCompletion, Instant due) {
        if (lastCompletion != null) {
            return null;
        }
        return due;
    }

    private static int remainingAfter(final int attempt) {
        if (attempt == 1) {
            return 5;
        }
        return 0;
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
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

    private static final class RecordingCycle implements ShadowScoringCycle {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private Runnable whenStopped = () -> { };
        private IntSupplier whenWritten = () -> 0;

        @Override
        public boolean start() {
            return true;
        }

        @Override
        public boolean tick() {
            return true;
        }

        @Override
        public int flush() {
            return 0;
        }

        @Override
        public void stopAccepting() {
            calls.add("stop_accepting");
            whenStopped.run();
        }

        @Override
        public int flushRemaining() {
            calls.add("final_write");
            return whenWritten.getAsInt();
        }
    }
}
