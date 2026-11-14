package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.ScoringComparisonJob;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ShadowScoringJob;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ForecastScoringExecutorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastScoringExecutorConfiguration.class)
        .withBean(ShadowScoringCycle.class, NoCycle::new)
        .withBean(CompareShadowScoring.class, () -> () -> { });

    @Test
    void 채점_스위치가_꺼져_있으면_채점_줄과_스레드와_주기_작업을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.scoring=off")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ScoringFeed.class);
                assertThat(context).doesNotHaveBean(ShadowScoringJob.class);
                assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                    .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
                assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().startsWith(ScoringExecutor.THREAD_PREFIX));
            });
    }

    @Test
    void 그림자_실행이_돌지_않으면_채점을_켜도_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=false", "forecast.scoring=shadow")
            .run(context -> assertThat(context).doesNotHaveBean(ScoringFeed.class));
        runner.withPropertyValues("forecast.enabled=false", "forecast.shadow.enabled=true", "forecast.scoring=shadow")
            .run(context -> assertThat(context).doesNotHaveBean(ScoringFeed.class));
        runner.withPropertyValues("forecast.enabled=false", "forecast.pipeline=v2", "forecast.scoring=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ScoringFeed.class));
    }

    @Test
    void 본선이_새_경로이면_채점_줄과_주기_작업을_등록하고_옛_정산_대조_작업은_등록하지_않는다() {
        for (String scoring : new String[] {"shadow", "v2"}) {
            runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v2", "forecast.scoring=" + scoring)
                .run(context -> {
                    assertThat(context).hasSingleBean(ScoringExecutor.class);
                    assertThat(context).hasSingleBean(ShadowScoringJob.class);
                    assertThat(context).doesNotHaveBean(ScoringComparisonJob.class);
                });
        }
    }

    @Test
    void 그림자_실행과_채점을_켜면_채점_줄과_주기_작업과_옛_정산_대조_작업을_기본값으로_등록한다() {
        for (String scoring : new String[] {"shadow"}) {
            runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true",
                "forecast.scoring=" + scoring).run(context -> {
                    ForecastPipelineProperties properties = context.getBean(ForecastPipelineProperties.class);

                    assertThat(context).hasSingleBean(ScoringExecutor.class);
                    assertThat(context).hasSingleBean(ShadowScoringJob.class);
                    assertThat(context).hasSingleBean(ScoringComparisonJob.class);
                    assertThat(properties.scoringFlushInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.scoringQueueCapacity()).isEqualTo(10_000);
                    assertThat(properties.scoringComparisonInterval()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.scoringComparisonDelay()).isEqualTo(Duration.ofHours(3));
                    assertThat(properties.scoringWithoutShadow(true)).isFalse();
                });
        }
    }

    @Test
    void 채점_줄_길이가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.scoring=shadow",
            "forecast.scoring-queue-capacity=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 그림자_실행_없이_채점만_켠_설정을_가려낸다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.scoring=shadow")
            .run(context -> {
                ForecastPipelineProperties properties = context.getBean(ForecastPipelineProperties.class);

                assertThat(properties.scoringWithoutShadow(false)).isTrue();
                assertThat(properties.scoringWithoutShadow(true)).isFalse();
            });
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
    }
}
