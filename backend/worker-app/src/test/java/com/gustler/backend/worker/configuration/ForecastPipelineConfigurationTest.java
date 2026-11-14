package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForecastPipelineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerSchedulingConfig.class, ForecastPipelineConfiguration.class)
        .withBean(MaintainPipelinePartitions.class, CountingPartitions::new);

    @Test
    void 스위치가_모두_기본값이면_파티션_유지_작업과_스위치_설정을_등록하지_않는다() {
        runner.withPropertyValues("forecast.pipeline=v1", "forecast.shadow.enabled=false", "forecast.scoring=off")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PipelinePartitionJob.class);
                assertThat(context).doesNotHaveBean(ForecastPipelineProperties.class);
                assertThat(((CountingPartitions) context.getBean(MaintainPipelinePartitions.class)).calls.get()).isZero();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"forecast.shadow.enabled=true", "forecast.pipeline=v2", "forecast.scoring=shadow",
        "forecast.scoring=v2"})
    void 스위치_하나라도_켜면_기동_직후에_파티션을_한_번_만든다(String switchOn) {
        runner.withPropertyValues(switchOn).run(context -> {
            CountingPartitions partitions = (CountingPartitions) context.getBean(MaintainPipelinePartitions.class);

            assertThat(context).hasSingleBean(PipelinePartitionJob.class);
            assertThat(context.getBean(ForecastPipelineProperties.class).usesPipelineTables()).isTrue();
            assertThat(partitions.started.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    @Test
    void 스위치_값을_바인딩하고_기본값을_채운다() {
        runner.withPropertyValues("forecast.pipeline=V2", "forecast.scoring=shadow").run(context -> {
            ForecastPipelineProperties actual = context.getBean(ForecastPipelineProperties.class);

            assertThat(actual.pipeline()).isEqualTo(ForecastPipelineProperties.Pipeline.V2);
            assertThat(actual.scoring()).isEqualTo(ForecastPipelineProperties.Scoring.SHADOW);
            assertThat(actual.shadow().enabled()).isFalse();
            assertThat(actual.lanes()).isEqualTo(2);
            assertThat(actual.replayWindow()).isEqualTo(Duration.ofHours(6));
        });
    }

    @Test
    void 노선별_실행_줄_수가_1보다_작으면_기동하지_않는다() {
        runner.withPropertyValues("forecast.shadow.enabled=true", "forecast.lanes=0")
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
        public Report maintain() {
            calls.incrementAndGet();
            started.countDown();
            return new Report(List.of(), List.of(), Map.of());
        }
    }
}
