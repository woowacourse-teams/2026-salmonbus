package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ForecastPipelineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, ForecastPipelineConfiguration.class)
        .withBean(MaintainPipelinePartitions.class, CountingPartitions::new);

    @Test
    void 예보와_수집이_모두_꺼져_있으면_파티션_유지_작업을_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=false", "collection.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PipelinePartitionJob.class);
                CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);
                assertThat(partitions.calls.get()).isZero();
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(PipelinePartitionJob.class));
    }

    @Test
    void 예보가_켜져_있으면_컨텍스트가_뜨기_전에_파티션_유지를_한_번_끝내고_그_뒤로는_한_시간마다_한다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);
            List<FixedDelayTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(scheduled -> scheduled.getTask())
                .filter(task -> task.toString().contains("PipelinePartitionJob.preparePartitions"))
                .map(FixedDelayTask.class::cast)
                .toList();

            assertSoftly(softly -> {
                softly.assertThat(context.getBeansOfType(PipelinePartitionJob.class)).hasSize(1);
                softly.assertThat(partitions.calls).as("주기 실행을 기다리지 않고 이미 한 번 끝냈다").hasValue(1);
                softly.assertThat(tasks).singleElement().satisfies(task -> {
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                });
            });
        });
    }

    @Test
    void 예보가_꺼져_있어도_수집이_켜진_worker는_관측_테이블을_위해_컨텍스트가_뜨기_전에_파티션_유지를_한_번_끝낸다() {
        runner.withPropertyValues("collection.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);

            assertThat(context).hasSingleBean(PipelinePartitionJob.class);
            assertThat(partitions.calls).hasValue(1);
        });
    }

    @Test
    void 예보_설정이_잘못돼도_수집만_켠_worker의_파티션_유지_작업은_등록한다() {
        runner.withPropertyValues("collection.enabled=true", "forecast.enabled=false", "forecast.lanes=0",
                "forecast.staleness=0s")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PipelinePartitionJob.class));
    }

    @Test
    void 첫_유지가_실패해도_컨텍스트는_뜬다() {
        new ApplicationContextRunner()
            .withUserConfiguration(WorkerSchedulingConfig.class, ForecastPipelineConfiguration.class)
            .withBean(MaintainPipelinePartitions.class, () -> () -> {
                throw new IllegalStateException("db down");
            })
            .withPropertyValues("collection.enabled=true")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PipelinePartitionJob.class));
    }

    @Test
    void 유지_작업이_실패해도_예외를_밖으로_던지지_않는다() {
        // given
        PipelinePartitionJob job = new PipelinePartitionJob(() -> {
            throw new IllegalStateException("db down");
        });

        // when
        Throwable actual = catchThrowable(job::preparePartitions);

        // then
        assertThat(actual).isNull();
    }

    static final class CountingPartitions implements MaintainPipelinePartitions {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public MaintainPipelinePartitions.Report maintain() {
            calls.incrementAndGet();
            return new MaintainPipelinePartitions.Report(List.of(), List.of(), Map.of());
        }
    }
}
