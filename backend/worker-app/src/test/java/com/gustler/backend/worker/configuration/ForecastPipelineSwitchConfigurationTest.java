package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.worker.scheduling.ArrivalLabelJob;
import com.gustler.backend.worker.scheduling.ForecastTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

class ForecastPipelineSwitchConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(ForecastPipelineSwitchConfiguration.class, ArrivalLabelJob.class, ForecastTrigger.class)
        .withBean(EvaluateForecasts.class, () -> mock(EvaluateForecasts.class))
        .withBean(PublishPendingForecasts.class, () -> mock(PublishPendingForecasts.class))
        .withBean("forecastTaskScheduler", TaskExecutor.class, SyncTaskExecutor::new)
        .withPropertyValues("forecast.enabled=true", "forecast.settlement-interval=60s", "forecast.interval=30s");

    @ParameterizedTest
    @CsvSource({"v1,false,off", "v1,true,off", "v1,true,shadow", "v2,false,shadow", "v2,false,v2", "v2,true,v2"})
    void 받아들이는_스위치_조합이면_기동한다(String pipeline, String shadow, String scoring) {
        runner.withPropertyValues("forecast.pipeline=" + pipeline, "forecast.shadow.enabled=" + shadow,
            "forecast.scoring=" + scoring).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({"v1,false,shadow", "v1,false,v2", "v1,true,v2", "v2,false,off", "v2,true,off"})
    void 받아들이지_않는_스위치_조합이면_기동하지_않는다(String pipeline, String shadow, String scoring) {
        runner.withPropertyValues("forecast.pipeline=" + pipeline, "forecast.shadow.enabled=" + shadow,
            "forecast.scoring=" + scoring).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 예보를_끈_worker는_스위치_조합을_보지_않는다() {
        runner.withPropertyValues("forecast.enabled=false", "forecast.pipeline=v1", "forecast.scoring=v2")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ForecastPipelineProperties.class);
                assertThat(context).doesNotHaveBean(ArrivalLabelJob.class);
            });
    }

    @Test
    void 새_채점이_본선이면_옛_정산_작업을_등록하지_않고_그_전까지는_등록한다() {
        runner.withPropertyValues("forecast.pipeline=v2", "forecast.scoring=v2")
            .run(context -> assertThat(context).doesNotHaveBean(ArrivalLabelJob.class));
        runner.withPropertyValues("forecast.pipeline=v2", "forecast.scoring=shadow")
            .run(context -> assertThat(context).hasSingleBean(ArrivalLabelJob.class));
        runner.run(context -> assertThat(context).hasSingleBean(ArrivalLabelJob.class));
    }

    @Test
    void 본선이_새_경로이면_옛_발행_트리거를_등록하지_않는다() {
        runner.run(context -> assertThat(context).hasSingleBean(ForecastTrigger.class));
        runner.withPropertyValues("forecast.pipeline=v2", "forecast.scoring=shadow")
            .run(context -> assertThat(context).doesNotHaveBean(ForecastTrigger.class));
    }
}
