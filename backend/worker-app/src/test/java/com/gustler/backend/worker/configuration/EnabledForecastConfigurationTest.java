package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.worker.scheduling.ForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ScoringJob;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class EnabledForecastConfigurationTest {
    private static final String[] VALID_SETTINGS = {
        "forecast.interval=30s",
        "forecast.staleness=5m",
        "forecast.batch-limit=20"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(WorkerSchedulingConfig.class, EnabledForecastConfiguration.class)
        .withBean(RunForecasts.class, NoForecasts::new)
        .withBean(ScoringCycle.class, NoCycle::new)
        .withPropertyValues(VALID_SETTINGS);

    @Test
    void 예보가_꺼져_있으면_정책과_실행_줄과_채점_줄과_주기_작업을_만들지_않고_설정도_읽지_않는다() {
        // given
        Set<Thread> threadsBefore = forecastThreads();

        // when
        runner.withPropertyValues("forecast.enabled=false", "forecast.staleness=0s", "forecast.batch-limit=0",
                "forecast.lanes=0")
            .run(context -> {

                // then
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ForecastProperties.class);
                assertThat(context).doesNotHaveBean(ForecastPolicy.class);
                assertThat(context).doesNotHaveBean(ForecastLanes.class);
                assertThat(context).doesNotHaveBean(ObservationDeliveryHook.class);
                assertThat(context).doesNotHaveBean(ScoringFeed.class);
                assertThat(context).doesNotHaveBean(ScoringJob.class);
                assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                    .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
                assertThat(forecastThreads()).as("이 구성이 새로 띄운 스레드가 없다").isSubsetOf(threadsBefore);
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(ForecastLanes.class)
            .doesNotHaveBean(ScoringFeed.class));
    }

    /** 같은 JVM의 다른 시험 컨텍스트가 띄워 둔 스레드가 있을 수 있어, 이 시험 전후의 스레드를 견준다. */
    private static Set<Thread> forecastThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(RouteLanes.THREAD_PREFIX)
                || thread.getName().startsWith(ScoringExecutor.THREAD_PREFIX))
            .collect(Collectors.toSet());
    }

    @Test
    void 예보가_켜져_있으면_정책과_줄_수만큼의_실행_줄과_수집_신호와_줍기와_채점_줄과_주기_작업을_함께_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.lanes=3")
            .run(context -> {
                List<String> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(task -> task.getTask().toString())
                    .toList();

                assertSoftly(softly -> {
                    softly.assertThat(context.getBean(ForecastPolicy.class))
                        .isEqualTo(new ForecastPolicy(Duration.ofMinutes(5), 20));
                    softly.assertThat(context.getBean(RouteLanes.class).count()).isEqualTo(3);
                    softly.assertThat(context.getBean(ObservationDeliveryHook.class))
                        .isInstanceOf(ForecastTrigger.class);
                    softly.assertThat(context.getBeansOfType(ScoringExecutor.class)).hasSize(1);
                    softly.assertThat(context.getBeansOfType(ScoringJob.class)).hasSize(1);
                    softly.assertThat(tasks).anyMatch(task -> task.contains("ForecastTrigger.pickUp"))
                        .anyMatch(task -> task.contains("ScoringJob.flush"));
                });
            });
    }

    @Test
    void 실행_줄_수의_기본값은_2이고_줄_길이와_채점_쓰기_간격은_설정_키로_두던_때의_기본값과_같다() {
        runner.withPropertyValues("forecast.enabled=true")
            .run(context -> {
                List<FixedDelayTask> flushes = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(scheduled -> scheduled.getTask())
                    .filter(task -> task.toString().contains("ScoringJob.flush"))
                    .map(FixedDelayTask.class::cast)
                    .toList();

                assertSoftly(softly -> {
                    softly.assertThat(context.getBean(ForecastProperties.class).lanes()).isEqualTo(2);
                    softly.assertThat(context.getBean(RouteLanes.class).count()).isEqualTo(2);
                    softly.assertThat(RouteLanes.QUEUE_CAPACITY).isEqualTo(1_000);
                    softly.assertThat(ScoringExecutor.QUEUE_CAPACITY).isEqualTo(10_000);
                    softly.assertThat(flushes).singleElement().satisfies(flush -> {
                        assertThat(flush.getIntervalDuration()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(flush.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(5));
                    });
                });
            });
    }

    @Test
    void 읽지_않는_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.compact-settlement-write=true",
                "forecast.settlement-interval=0s", "forecast.settlement-minimum-interval=0s",
                "forecast.pending-limit=0", "forecast.arrival-limit=0")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RouteLanes.class);
                assertThat(context).hasSingleBean(ScoringExecutor.class);
                assertThat(context).hasSingleBean(ScoringJob.class);
                assertThat(context).getBean(ObservationDeliveryHook.class).isInstanceOf(ForecastTrigger.class);
            });
    }

    @Test
    void 노선별_실행_줄_수가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.lanes=0")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessage("forecast.lanes 는 1 이상이어야 한다: 0"));
    }

    @Test
    void 환경변수로_신선도_창을_주면_기동을_멈춘다() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("FORECAST_STALENESS", "3m"))))
            .withPropertyValues("forecast.enabled=true")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("FORECAST_STALENESS"));
    }

    @Test
    void 예보를_끄면_신선도_창_환경변수가_있어도_기동한다() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("FORECAST_STALENESS", "3m"))))
            .withPropertyValues("forecast.enabled=false", "forecast.same-day-initialization.retry-interval=0ms")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ForecastPolicy.class));
    }

    @Test
    void 컨텍스트를_닫으면_실행_줄이_새_일을_받지_않고_기다리던_일을_버리며_돌던_일이_끝난_뒤에_연결_풀을_닫는다() {
        // given
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean eventAccepted = new AtomicBoolean();
        AtomicBoolean acceptedAfterClose = new AtomicBoolean(true);

        // when
        runner.withBean("dataSource", HikariDataSource.class, () -> new RecordingPool(events))
            .withPropertyValues("forecast.enabled=true", "forecast.lanes=1")
            .run(context -> {
                RouteLanes lanes = context.getBean(RouteLanes.class);
                ScoringExecutor scoring = context.getBean(ScoringExecutor.class);
                CountDownLatch started = new CountDownLatch(1);
                lanes.submit("key", () -> {
                    started.countDown();
                    untilClosed(lanes);
                    acceptedAfterClose.set(lanes.submit("key", () -> events.add("submitted_after_close")));
                    eventAccepted.set(scoring.submit(() -> events.add("event_scored")));
                    events.add("running_finished");
                });
                lanes.submit("key", () -> events.add("waiting_ran"));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            });

        // then
        assertSoftly(softly -> {
            softly.assertThat(events).as("돌던 일이 끝난 뒤에 풀을 닫고, 기다리던 일과 닫은 뒤 넣은 일은 돌지 않는다")
                .filteredOn(event -> !event.equals("event_scored"))
                .containsExactly("running_finished", "pool_closed");
            softly.assertThat(acceptedAfterClose).isFalse();
            softly.assertThat(eventAccepted).as("채점 줄은 실행 줄이 돌던 일을 끝낼 때까지 사건을 받는다").isTrue();
        });
    }

    @Test
    void 컨텍스트가_닫힐_때_돌던_수집_회차가_넘긴_일까지는_실행_줄이_받아_연결_풀을_닫기_전에_끝낸다() {
        // given
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean accepted = new AtomicBoolean();

        // when
        runner.withBean("dataSource", HikariDataSource.class, () -> new RecordingPool(events))
            .withPropertyValues("forecast.enabled=true", "forecast.lanes=1")
            .run(context -> {
                RouteLanes lanes = context.getBean(RouteLanes.class);
                ThreadPoolTaskScheduler collection = context.getBean("collectionTaskScheduler",
                    ThreadPoolTaskScheduler.class);
                CountDownLatch started = new CountDownLatch(1);
                collection.execute(() -> {
                    started.countDown();
                    untilClosing(collection);
                    accepted.set(lanes.submit("key", () -> {
                        untilClosed(lanes);
                        events.add("last_round_forecast");
                    }));
                    pause(100);
                    events.add("round_finished");
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            });

        // then
        assertSoftly(softly -> {
            softly.assertThat(accepted).isTrue();
            softly.assertThat(events).containsExactly("round_finished", "last_round_forecast", "pool_closed");
        });
    }

    /**
     * 컨텍스트가 닫히기 시작해 스케줄러가 새 일을 받지 않게 된 뒤에도 잠시 더 돈다. 컨텍스트는 시험이 끝날 때 반드시 닫히므로 한도
     * 없이 기다린다.
     */
    private static void untilClosing(ThreadPoolTaskScheduler scheduler) {
        while (!scheduler.getScheduledThreadPoolExecutor().isShutdown()) {
            pause(5);
        }
        pause(200);
    }

    /**
     * 실행 줄이 닫히고 기다리던 일을 모두 버린 것을 본 뒤에도 잠시 더 돈다. 닫는 쪽이 기다리지 않으면 그사이 연결 풀이 먼저 닫힌다.
     * 실행 줄은 스케줄러가 돌던 회차를 끝낸 뒤에 닫히므로, 회차가 넘긴 일이 이것을 기다리면 회차보다 늦게 끝난다. 컨텍스트는 시험이
     * 끝날 때 반드시 닫히므로 한도 없이 기다린다.
     */
    private static void untilClosed(RouteLanes lanes) {
        while (lanes.isRunning() || lanes.queued() > 0) {
            pause(5);
        }
        pause(200);
    }

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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

    private static final class NoForecasts implements RunForecasts {
        @Override
        public void batchStored(final long observationBatchId, final long routeVersionId) {
        }

        @Override
        public void pickUp() {
        }
    }

    private static final class NoCycle implements ScoringCycle {
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
    }
}
