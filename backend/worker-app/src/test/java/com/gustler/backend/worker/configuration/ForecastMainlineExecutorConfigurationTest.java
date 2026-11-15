package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ForecastMainlineExecutorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, ForecastMainlineExecutorConfiguration.class)
        .withBean(ForecastPipeline.class, NoPipeline::new)
        .withPropertyValues("forecast.interval=30s");

    @Test
    void 본선이_옛_경로이면_노선별_실행_줄과_신호와_줍기를_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v1", "forecast.shadow.enabled=true")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ForecastLanes.class);
                assertThat(context).doesNotHaveBean(ObservationDeliveryHook.class);
                assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().startsWith(RouteLanes.THREAD_PREFIX));
            });
        runner.withPropertyValues("forecast.enabled=false", "forecast.pipeline=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastLanes.class));
    }

    @Test
    void 본선이_새_경로이면_줄_수만큼_실행_줄과_수집_신호와_줍기_작업을_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v2", "forecast.scoring=v2",
                "forecast.lanes=3")
            .run(context -> {
                assertThat(context.getBean(RouteLanes.class).count()).isEqualTo(3);
                assertThat(context).getBean(ObservationDeliveryHook.class).isInstanceOf(PipelineForecastTrigger.class);
                assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(task -> task.getTask().toString()))
                    .anyMatch(task -> task.contains("PipelineForecastTrigger.pickUp"));
            });
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
