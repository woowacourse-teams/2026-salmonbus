package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class ForecastScoringConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastScoringConfiguration.class)
        .withBean(ForecastLanes.class, () -> (key, task) -> true)
        .withBean(ScoringFeed.class, () -> task -> true)
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(VehicleCapacityCache.class, () -> mock(VehicleCapacityCache.class))
        .withBean(JdbcForecastRecordStore.class, () -> mock(JdbcForecastRecordStore.class))
        .withBean(StopPassStore.class, () -> mock(StopPassStore.class))
        .withBean(PassLabelStore.class, () -> mock(PassLabelStore.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ForecastRuntime.class, () -> Optional::empty)
        .withBean(RouteVersionQuery.class, () -> List::of)
        .withBean(RouteStopsQuery.class, () -> mock(RouteStopsQuery.class))
        .withBean(ForecastPolicy.class, () -> new ForecastPolicy(Duration.ofMinutes(10), 20))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 예보가_꺼져_있으면_예보와_채점을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ForecastPipeline.class);
            assertThat(context).doesNotHaveBean(PipelineEvents.class);
        });
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ForecastPipeline.class);
            assertThat(context).doesNotHaveBean(PipelineEvents.class);
        });
    }

    @Test
    void 예보가_켜져_있으면_예보_서비스와_그_사건을_받는_채점을_함께_등록한다() {
        runner.withPropertyValues("forecast.enabled=true").run(context -> {
            assertThat(context).getBean(ForecastPipeline.class).isInstanceOf(ForecastPipelineService.class);
            assertThat(context).getBean(PipelineEvents.class).isInstanceOf(ShadowScoringService.class);
            assertThat(context).getBean(ShadowScoringCycle.class).isInstanceOf(ShadowScoringService.class);
        });
    }

    @Test
    void 읽지_않는_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v1", "forecast.shadow.enabled=true",
                "forecast.scoring=off", "forecast.scoring-comparison-delay=-1s", "forecast.replay-window=0s",
                "forecast.replay-extended-window=13h", "forecast.alighting.rush-only=false",
                "forecast.alighting.maximum-history-rows=0",
                "forecast.alighting.causal-evaluation.maximum-observation-rows=0")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ForecastPipeline.class)
                .hasSingleBean(ShadowScoringService.class));
    }
}
