package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class ForecastScoringConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastScoringConfiguration.class)
        .withBean(ScoringFeed.class, () -> task -> true)
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(JdbcForecastRecordStore.class, () -> mock(JdbcForecastRecordStore.class))
        .withBean(StopPassStore.class, () -> mock(StopPassStore.class))
        .withBean(PassLabelStore.class, () -> mock(PassLabelStore.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 예보가_꺼져_있으면_사건을_받을_채점을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(PipelineEvents.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(PipelineEvents.class));
    }

    @Test
    void 예보가_켜져_있으면_예보의_사건을_받는_채점을_등록한다() {
        runner.withPropertyValues("forecast.enabled=true")
            .run(context -> assertThat(context).getBean(PipelineEvents.class).isInstanceOf(ShadowScoringService.class));
    }

    @Test
    void 읽지_않는_채점_설정_키가_남아_있어도_예보가_켜져_있으면_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.scoring=off", "forecast.pipeline=v1",
                "forecast.scoring-comparison-delay=-1s")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ShadowScoringService.class));
    }
}
