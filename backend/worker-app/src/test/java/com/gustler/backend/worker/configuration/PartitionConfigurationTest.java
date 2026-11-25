package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.MaintainPartitions;
import com.gustler.backend.worker.scheduling.PartitionJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class PartitionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, PartitionConfiguration.class)
        .withBean(MaintainPartitions.class, CountingPartitions::new);

    @Test
    void 예보와_수집이_모두_꺼져_있으면_파티션_유지_작업을_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=false", "collection.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PartitionJob.class);
                CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPartitions.class);
                assertThat(partitions.prepared.get() + partitions.maintained.get()).isZero();
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(PartitionJob.class));
    }

    @Test
    void 예보가_켜져_있으면_컨텍스트가_뜨기_전에_파티션을_한_번_만들고_그_뒤로는_한_시간마다_유지한다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPartitions.class);
            List<FixedDelayTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(scheduled -> scheduled.getTask())
                .filter(task -> task.toString().contains("PartitionJob.preparePartitions"))
                .map(FixedDelayTask.class::cast)
                .toList();

            assertSoftly(softly -> {
                softly.assertThat(context.getBeansOfType(PartitionJob.class)).hasSize(1);
                softly.assertThat(partitions.prepared).as("주기 실행을 기다리지 않고 이미 한 번 만들었다").hasValue(1);
                softly.assertThat(partitions.maintained).as("기동 때는 기본 파티션의 행 수를 세지 않는다").hasValue(0);
                softly.assertThat(tasks).singleElement().satisfies(task -> {
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                });
            });
        });
    }

    @Test
    void 예보가_꺼져_있어도_수집이_켜진_worker는_관측_테이블을_위해_컨텍스트가_뜨기_전에_파티션을_한_번_만든다() {
        runner.withPropertyValues("collection.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPartitions.class);

            assertThat(context).hasSingleBean(PartitionJob.class);
            assertThat(partitions.prepared).hasValue(1);
        });
    }

    @Test
    void 주기_실행은_파티션을_만들고_기본_파티션의_줄_수까지_센다() {
        // given
        CountingPartitions partitions = new CountingPartitions();
        PartitionJob job = new PartitionJob(partitions);

        // when
        job.preparePartitions();

        // then
        assertSoftly(softly -> {
            softly.assertThat(partitions.maintained).hasValue(1);
            softly.assertThat(partitions.prepared).hasValue(0);
        });
    }

    @Test
    void 예보_설정이_잘못돼도_수집만_켠_worker의_파티션_유지_작업은_등록한다() {
        runner.withPropertyValues("collection.enabled=true", "forecast.enabled=false", "forecast.lanes=0",
                "forecast.staleness=0s")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PartitionJob.class));
    }

    @Test
    void 첫_유지가_실패해도_컨텍스트는_뜬다() {
        new ApplicationContextRunner()
            .withUserConfiguration(WorkerSchedulingConfig.class, PartitionConfiguration.class)
            .withBean(MaintainPartitions.class, FailingPartitions::new)
            .withPropertyValues("collection.enabled=true")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PartitionJob.class));
    }

    @Test
    void 유지_작업이_실패해도_예외를_밖으로_던지지_않는다() {
        // given
        PartitionJob job = new PartitionJob(new FailingPartitions());

        // when
        Throwable first = catchThrowable(job::afterPropertiesSet);
        Throwable periodic = catchThrowable(job::preparePartitions);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).isNull();
            softly.assertThat(periodic).isNull();
        });
    }

    static final class CountingPartitions implements MaintainPartitions {
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger maintained = new AtomicInteger();

        @Override
        public MaintainPartitions.Report prepare() {
            prepared.incrementAndGet();
            return new MaintainPartitions.Report(List.of(), List.of(), Map.of());
        }

        @Override
        public MaintainPartitions.Report maintain() {
            maintained.incrementAndGet();
            return new MaintainPartitions.Report(List.of(), List.of(), Map.of());
        }
    }

    static final class FailingPartitions implements MaintainPartitions {
        @Override
        public MaintainPartitions.Report prepare() {
            throw new IllegalStateException("db down");
        }

        @Override
        public MaintainPartitions.Report maintain() {
            throw new IllegalStateException("db down");
        }
    }
}
