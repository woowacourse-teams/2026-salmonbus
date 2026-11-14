package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.worker.scheduling.ShadowForecastExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForecastShadowExecutorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastShadowExecutorConfiguration.class);

    @Test
    void 그림자_스위치가_꺼져_있으면_실행_줄과_스레드를_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ShadowForecastFeed.class);
            assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().startsWith(ShadowForecastExecutor.THREAD_PREFIX));
        });
    }

    @Test
    void 예보가_꺼져_있거나_본선이_새_경로면_실행_줄을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=false", "forecast.shadow.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ShadowForecastFeed.class));
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.pipeline=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ShadowForecastFeed.class));
    }

    @Test
    void 그림자를_켜면_줄_길이_기본값_200으로_실행_줄을_만든다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ShadowForecastExecutor.class);
            assertThat(context.getBean(ForecastPipelineProperties.class).shadow().queueCapacity()).isEqualTo(200);
        });
    }

    @Test
    void 줄_길이가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true",
            "forecast.shadow.queue-capacity=0").run(context -> assertThat(context).hasFailed());
    }
}
