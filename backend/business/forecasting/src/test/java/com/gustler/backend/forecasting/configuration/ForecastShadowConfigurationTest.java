package com.gustler.backend.forecasting.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.publication.LegacyForecastListener;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class ForecastShadowConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ForecastShadowConfiguration.class)
        .withBean(ShadowForecastFeed.class, () -> task -> { })
        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
        .withBean(VehicleCapacityCache.class, () -> mock(VehicleCapacityCache.class))
        .withBean(ForecastRecordStore.class, () -> mock(ForecastRecordStore.class))
        .withBean(StopPassStore.class, () -> mock(StopPassStore.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 그림자_스위치가_꺼져_있으면_옛_경로가_알림을_보낼_대상을_만들지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(LegacyForecastListener.class));
    }

    @Test
    void 본선이_새_경로이거나_예보가_꺼져_있으면_그림자를_돌리지_않는다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true", "forecast.pipeline=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ShadowForecastService.class));
        runner.withPropertyValues("forecast.enabled=false", "forecast.shadow.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ShadowForecastService.class));
    }

    @Test
    void 옛_경로가_본선이고_그림자를_켜면_알림을_받는_그림자_실행을_등록한다() {
        runner.withPropertyValues("forecast.enabled=true", "forecast.shadow.enabled=true")
            .run(context -> assertThat(context).getBean(LegacyForecastListener.class)
                .isInstanceOf(ShadowForecastService.class));
    }
}
