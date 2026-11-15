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
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
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
        .withBean(ForecastPolicy.class, () -> new ForecastPolicy(Duration.ofMinutes(10), 20))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 예보가_꺼져_있으면_예보_구성_요소를_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastPipeline.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(ForecastPipeline.class));
    }

    @Test
    void 예보가_켜져_있으면_되감기_원본과_예보_서비스를_등록한다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            assertThat(context).getBean(ForecastPipeline.class).isInstanceOf(ForecastPipelineService.class);
            assertThat(context).hasSingleBean(JdbcReplaySource.class);
        });
    }

    @Test
    void 읽지_않는_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v1", "forecast.shadow.enabled=true",
                "forecast.scoring=off")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ForecastPipeline.class));
    }

    @Test
    void 늘린_되감기_창이_상한보다_길면_예보_서비스를_만들지_못한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.replay-extended-window=13h")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
        runner.withPropertyValues("forecast.enabled=true", "forecast.replay-window=3h",
                "forecast.replay-extended-window=12h")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ForecastPipeline.class));
    }
}
