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
    void 예보가_꺼져_있으면_노선별_실행_줄과_신호와_줍기를_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ForecastLanes.class);
                assertThat(context).doesNotHaveBean(ObservationDeliveryHook.class);
                assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().startsWith(RouteLanes.THREAD_PREFIX));
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(ForecastLanes.class));
    }

    @Test
    void 예보가_켜져_있으면_줄_수만큼_실행_줄과_수집_신호와_줍기_작업을_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.lanes=3")
            .run(context -> {
                assertThat(context.getBean(RouteLanes.class).count()).isEqualTo(3);
                assertThat(context).getBean(ObservationDeliveryHook.class).isInstanceOf(PipelineForecastTrigger.class);
                assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(task -> task.getTask().toString()))
                    .anyMatch(task -> task.contains("PipelineForecastTrigger.pickUp"));
            });
    }

    @Test
    void 읽지_않는_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v1", "forecast.shadow.enabled=true",
                "forecast.shadow.queue-capacity=0", "forecast.scoring=off", "forecast.scoring-comparison-delay=-1s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RouteLanes.class);
                assertThat(context).getBean(ObservationDeliveryHook.class).isInstanceOf(PipelineForecastTrigger.class);
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
