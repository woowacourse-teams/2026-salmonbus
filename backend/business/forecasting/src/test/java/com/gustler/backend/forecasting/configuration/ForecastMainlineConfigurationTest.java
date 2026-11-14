package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcReplaySource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class ForecastMainlineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastMainlineConfiguration.class)
        .withBean(ForecastLanes.class, () -> (key, task) -> true)
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(VehicleCapacityCache.class, () -> mock(VehicleCapacityCache.class))
        .withBean(ForecastRecordStore.class, () -> mock(ForecastRecordStore.class))
        .withBean(StopPassStore.class, () -> mock(StopPassStore.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ForecastRuntime.class, () -> Optional::empty)
        .withBean(RouteVersionQuery.class, () -> List::of)
        .withBean(RouteStopsQuery.class, () -> mock(RouteStopsQuery.class))
        .withBean(ForecastPolicy.class, () -> new ForecastPolicy(Duration.ofMinutes(10), 20, 3000, 400))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 본선이_옛_경로이거나_예보가_꺼져_있으면_새_경로_본선을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastPipeline.class));
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v1", "forecast.shadow.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastPipeline.class));
        runner.withPropertyValues("forecast.enabled=false", "forecast.pipeline=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastPipeline.class));
    }

    @Test
    void 본선을_새_경로로_바꾸면_착수_시각을_예보_실행에서_먼저_읽는_되감기로_본선을_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=V2").run(context -> {
            assertThat(context).getBean(ForecastPipeline.class).isInstanceOf(ForecastPipelineService.class);
            assertThat(context.getBean(JdbcReplaySource.class).axis()).isEqualTo(JdbcReplaySource.Axis.RECORDED_RUN);
        });
    }
}
