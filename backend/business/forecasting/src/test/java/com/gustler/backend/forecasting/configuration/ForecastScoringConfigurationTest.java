package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastService;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class ForecastScoringConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastShadowConfiguration.class, ForecastScoringConfiguration.class)
        .withBean(ShadowForecastFeed.class, () -> task -> { })
        .withBean(ScoringFeed.class, () -> task -> true)
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(VehicleCapacityCache.class, () -> mock(VehicleCapacityCache.class))
        .withBean(JdbcForecastRecordStore.class, () -> mock(JdbcForecastRecordStore.class))
        .withBean(StopPassStore.class, () -> mock(StopPassStore.class))
        .withBean(PassLabelStore.class, () -> mock(PassLabelStore.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 채점_스위치가_꺼져_있으면_사건을_받을_채점과_대조를_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.scoring=off")
            .run(context -> {
                assertThat(context).doesNotHaveBean(PipelineEvents.class);
                assertThat(context).doesNotHaveBean(CompareShadowScoring.class);
            });
    }

    @Test
    void 그림자_예보가_꺼져_있으면_채점을_켜도_등록하지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=false", "forecast.scoring=shadow")
            .run(context -> assertThat(context).doesNotHaveBean(PipelineEvents.class));
    }

    @Test
    void 그림자_예보와_채점을_켜면_그림자_예보의_사건을_받는_채점과_대조를_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.scoring=shadow")
            .run(context -> {
                assertThat(context).getBean(PipelineEvents.class).isInstanceOf(ShadowScoringService.class);
                assertThat(context).hasSingleBean(CompareShadowScoring.class);
            });
    }

    @Test
    void 본선이_새_경로면_본선의_사건을_받는_채점만_등록하고_옛_정산과_대조하지_않는다() {
        for (String scoring : new String[] {"shadow", "v2"}) {
            runner.withPropertyValues("forecast.enabled=true", "forecast.pipeline=v2", "forecast.scoring=" + scoring)
                .run(context -> {
                    assertThat(context).getBean(PipelineEvents.class).isInstanceOf(ShadowScoringService.class);
                    assertThat(context).doesNotHaveBean(CompareShadowScoring.class);
                    assertThat(context).doesNotHaveBean(ShadowForecastService.class);
                });
        }
    }
}
