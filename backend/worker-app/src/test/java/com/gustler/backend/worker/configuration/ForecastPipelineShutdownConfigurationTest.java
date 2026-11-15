package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.PipelineShutdown;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(OutputCaptureExtension.class)
class ForecastPipelineShutdownConfigurationTest {
    private static final String ENABLED = "forecast.enabled=true";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final RecordingCycle cycle = new RecordingCycle(events);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, ForecastMainlineExecutorConfiguration.class,
            ForecastScoringExecutorConfiguration.class, ForecastPipelineShutdownConfiguration.class)
        .withBean(ForecastPipeline.class, NoPipeline::new)
        .withPropertyValues("forecast.interval=1h", "forecast.scoring-flush-interval=1h");

    @AfterEach
    void 붙잡은_스레드를_푼다() {
        release.countDown();
    }

    @Test
    void 예보가_꺼져_있으면_종료_처리를_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(PipelineShutdown.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(PipelineShutdown.class));
    }

    @Test
    void 종료_처리는_노선별_실행_줄과_채점_줄을_비우고_마지막_쓰기를_한다() {
        runner.withBean(ShadowScoringCycle.class, () -> cycle)
            .withPropertyValues(ENABLED)
            .run(context -> {
                // given
                RouteLanes lanes = context.getBean(RouteLanes.class);
                ScoringExecutor scoring = context.getBean(ScoringExecutor.class);
                AtomicInteger scored = new AtomicInteger();
                for (int index = 0; index < 10; index++) {
                    lanes.submit("route-" + index, () -> scoring.submit(scored::incrementAndGet));
                }

                // when
                context.getBean(PipelineShutdown.class).stop();

                // then
                assertSoftly(softly -> {
                    softly.assertThat(scored).hasValue(10);
                    softly.assertThat(lanes.submit("route-0", scored::incrementAndGet)).isFalse();
                    softly.assertThat(scoring.submit(scored::incrementAndGet)).isFalse();
                    softly.assertThat(events).containsExactly("stop_accepting", "final_write");
                });
            });
    }

    @Test
    void 컨텍스트를_닫으면_수집을_멈추고_예보_줄과_채점_줄을_비운_뒤_마지막_쓰기를_하고_그_뒤에_연결_풀을_닫는다() {
        // given
        AtomicInteger collected = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger forecasted = new AtomicInteger();
        AtomicInteger scored = new AtomicInteger();
        AtomicInteger forecastedBeforeScoringStopped = new AtomicInteger(-1);
        AtomicInteger scoredBeforeFinalWrite = new AtomicInteger(-1);
        List<Boolean> collectionStopped = new CopyOnWriteArrayList<>();
        List<Boolean> poolClosedAtFinalWrite = new CopyOnWriteArrayList<>();
        RecordingPool pool = new RecordingPool(events);

        // when
        runner.withBean(ShadowScoringCycle.class, () -> cycle)
            .withBean("dataSource", HikariDataSource.class, () -> pool)
            .withPropertyValues(ENABLED)
            .run(context -> {
                RouteLanes lanes = context.getBean(RouteLanes.class);
                ScoringExecutor scoring = context.getBean(ScoringExecutor.class);
                ThreadPoolTaskScheduler collection = scheduler(context, "collectionTaskScheduler");
                cycle.whenStopped = () -> {
                    collectionStopped.add(collection.getScheduledThreadPoolExecutor().isTerminated());
                    forecastedBeforeScoringStopped.set(forecasted.get());
                };
                cycle.whenWritten = () -> {
                    scoredBeforeFinalWrite.set(scored.get());
                    poolClosedAtFinalWrite.add(pool.isClosed());
                };
                collection.schedule(() -> {
                    events.add("collect");
                    for (int route = 0; route < 4; route++) {
                        final boolean accepted = lanes.submit("route-" + route, () -> {
                            pause(10);
                            forecasted.incrementAndGet();
                            scoring.submit(() -> {
                                pause(5);
                                scored.incrementAndGet();
                            });
                        });
                        count(accepted, collected, rejected);
                        pause(5);
                    }
                }, ignored -> Instant.now().plusMillis(5));
                assertThat(waitUntil(() -> collected.get() >= 40)).isTrue();
            });
        pause(150);

        // then
        assertSoftly(softly -> {
            softly.assertThat(events.subList(events.lastIndexOf("collect") + 1, events.size()))
                .as("수집이 멈춘 뒤에 채점 멈춤, 마지막 쓰기, 풀 닫힘 순서다")
                .containsExactly("stop_accepting", "final_write", "pool_closed");
            softly.assertThat(collectionStopped).containsExactly(true);
            softly.assertThat(rejected).as("실행 줄을 닫은 뒤에 도착한 배치가 없다").hasValue(0);
            softly.assertThat(forecastedBeforeScoringStopped).as("채점을 멈추기 전에 받은 배치를 모두 예보했다")
                .hasValue(collected.get());
            softly.assertThat(scoredBeforeFinalWrite).as("마지막 쓰기 전에 받은 사건을 모두 채점했다")
                .hasValue(collected.get());
            softly.assertThat(forecasted).as("버린 배치가 없다").hasValue(collected.get());
            softly.assertThat(scored).as("버린 사건이 없다").hasValue(collected.get());
            softly.assertThat(poolClosedAtFinalWrite).containsExactly(false);
            softly.assertThat(pool.isClosed()).isTrue();
        });
    }

    @Test
    void 종료_한도가_아주_짧으면_컨텍스트를_닫을_때_남은_일을_버리고_제시간에_내려간다(CapturedOutput output) {
        // given
        AtomicInteger later = new AtomicInteger();
        AtomicLong closeStarted = new AtomicLong();

        // when
        runner.withBean(ShadowScoringCycle.class, () -> cycle)
            .withPropertyValues(ENABLED)
            .withPropertyValues("forecast.shutdown-timeout=200ms", "forecast.lanes=1")
            .run(context -> {
                RouteLanes lanes = context.getBean(RouteLanes.class);
                CountDownLatch started = new CountDownLatch(1);
                lanes.submit("key", () -> block(started));
                for (int index = 0; index < 6; index++) {
                    lanes.submit("key", later::incrementAndGet);
                }
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                closeStarted.set(System.nanoTime());
            });
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted.get());

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isBetween(150L, 3_000L);
            softly.assertThat(later).as("버린 배치는 처리하지 않는다").hasValue(0);
            softly.assertThat(events).containsExactly("stop_accepting", "final_write");
            softly.assertThat(output.getOut()).contains("event=pipeline_shutdown_abandoned")
                .contains("timeoutMs=200 intakeRunning=0 forecastQueued=6 forecastRunning=1 scoringQueued=0");
        });
    }

    @Test
    void 종료_한도의_기본값은_10초이고_음수이면_기동하지_않는다() {
        runner.withBean(ShadowScoringCycle.class, () -> cycle)
            .withPropertyValues(ENABLED)
            .run(context -> assertThat(context.getBean(ForecastPipelineProperties.class).shutdownTimeout())
                .isEqualTo(Duration.ofSeconds(10)));
        runner.withBean(ShadowScoringCycle.class, () -> cycle)
            .withPropertyValues(ENABLED)
            .withPropertyValues("forecast.shutdown-timeout=-1s")
            .run(context -> assertThat(context).hasFailed());
    }

    private static ThreadPoolTaskScheduler scheduler(ApplicationContext context, String name) {
        return context.getBean(name, ThreadPoolTaskScheduler.class);
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

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingCycle implements ShadowScoringCycle {
        private final List<String> events;
        private Runnable whenStopped = () -> { };
        private Runnable whenWritten = () -> { };

        private RecordingCycle(List<String> events) {
            this.events = events;
        }

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
            whenStopped.run();
            events.add("stop_accepting");
        }

        @Override
        public int flushRemaining() {
            whenWritten.run();
            events.add("final_write");
            return 0;
        }
    }

    /** 연결을 맺지 않는 실제 풀이다. 닫힌 시점만 적는다. */
    private static final class RecordingPool extends HikariDataSource {
        private final List<String> events;

        private RecordingPool(List<String> events) {
            this.events = events;
        }

        @Override
        public void close() {
            events.add("pool_closed");
            super.close();
        }
    }

    private static final class NoPipeline implements ForecastPipeline {
        @Override
        public void batchStored(final long observationBatchId, final long routeVersionId) {
        }

        @Override
        public void pickUp() {
        }
    }
}
