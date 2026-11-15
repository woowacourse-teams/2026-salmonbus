package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ShadowScoringJob;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ForecastScoringExecutorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastScoringExecutorConfiguration.class)
        .withBean(ShadowScoringCycle.class, NoCycle::new);

    @Test
    void 예보가_꺼져_있으면_채점_줄과_스레드와_주기_작업을_만들지_않는다() {
        // given
        Set<Thread> scoringThreadsBefore = scoringThreads();

        // when
        runner.withPropertyValues("forecast.enabled=false")
            .run(context -> {

                // then
                assertThat(context).doesNotHaveBean(ScoringFeed.class);
                assertThat(context).doesNotHaveBean(ShadowScoringJob.class);
                assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                    .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
                assertThat(scoringThreads()).as("이 구성이 새로 띄운 채점 스레드가 없다").isSubsetOf(scoringThreadsBefore);
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(ScoringFeed.class));
    }

    /** 같은 JVM의 다른 시험 컨텍스트가 띄워 둔 채점 스레드가 있을 수 있어, 이 시험 전후의 스레드를 견준다. */
    private static Set<Thread> scoringThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(ScoringExecutor.THREAD_PREFIX))
            .collect(Collectors.toSet());
    }

    @Test
    void 예보가_켜져_있으면_채점_줄과_주기_작업을_기본값으로_등록한다() {
        runner.withPropertyValues("forecast.enabled=true")
            .run(context -> {
                ForecastPipelineProperties properties = context.getBean(ForecastPipelineProperties.class);

                assertThat(context).hasSingleBean(ScoringExecutor.class);
                assertThat(context).hasSingleBean(ShadowScoringJob.class);
                assertSoftly(softly -> {
                    softly.assertThat(properties.scoringFlushInterval()).isEqualTo(Duration.ofSeconds(5));
                    softly.assertThat(properties.scoringQueueCapacity()).isEqualTo(10_000);
                });
            });
    }

    @Test
    void 읽지_않는_채점_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.scoring=off", "forecast.pipeline=v1",
                "forecast.scoring-comparison-interval=0s", "forecast.scoring-comparison-batch-limit=0")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ScoringExecutor.class);
                assertThat(context).hasSingleBean(ShadowScoringJob.class);
            });
    }

    @Test
    void 채점_줄_길이가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.scoring-queue-capacity=0")
            .run(context -> assertThat(context).hasFailed());
    }

    private static final class NoCycle implements ShadowScoringCycle {
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
        }

        @Override
        public int flushRemaining() {
            return 0;
        }
    }
}
