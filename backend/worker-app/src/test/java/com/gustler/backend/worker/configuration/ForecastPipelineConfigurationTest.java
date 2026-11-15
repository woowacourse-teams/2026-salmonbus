package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForecastPipelineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, ForecastPipelineConfiguration.class)
        .withBean(MaintainPipelinePartitions.class, CountingPartitions::new);

    @Test
    void 예보와_수집이_모두_꺼져_있으면_파티션_유지_작업과_설정을_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=false", "collection.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PipelinePartitionJob.class);
                assertThat(context).doesNotHaveBean(ForecastPipelineProperties.class);
                CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);
                assertThat(partitions.calls.get()).isZero();
            });
        runner.run(context -> assertThat(context).doesNotHaveBean(PipelinePartitionJob.class));
    }

    @Test
    void 예보가_켜져_있으면_기동_직후에_파티션을_한_번_만든다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);

            assertThat(context).hasSingleBean(PipelinePartitionJob.class);
            assertThat(partitions.started.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    @Test
    void 예보가_꺼져_있어도_수집이_켜진_worker는_관측_테이블을_위해_파티션_유지_작업을_등록한다() {
        runner.withPropertyValues("collection.enabled=true").run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);

            assertThat(context).hasSingleBean(PipelinePartitionJob.class);
            assertThat(partitions.started.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    @Test
    void 실행_줄과_되감기와_종료_설정의_기본값을_채운다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            ForecastPipelineProperties actual = context.getBean(ForecastPipelineProperties.class);

            assertSoftly(softly -> {
                softly.assertThat(actual.lanes()).isEqualTo(2);
                softly.assertThat(actual.laneQueueCapacity()).isEqualTo(1_000);
                softly.assertThat(actual.replayWindow()).isEqualTo(Duration.ofHours(6));
                softly.assertThat(actual.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
            });
        });
    }

    @Test
    void 노선별_실행_줄_수가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.lanes=0")
            .run(context -> assertThat(context).hasFailed());
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
        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        public MaintainPipelinePartitions.Report maintain() {
            calls.incrementAndGet();
            started.countDown();
            return new MaintainPipelinePartitions.Report(List.of(), List.of(), Map.of());
        }
    }
}
