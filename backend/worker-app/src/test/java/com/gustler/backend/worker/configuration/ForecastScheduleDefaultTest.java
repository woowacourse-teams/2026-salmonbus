package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import com.gustler.backend.worker.scheduling.PipelineShutdown;
import com.gustler.backend.worker.scheduling.ShadowScoringJob;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 계수 번들이 없어 활성 배포가 하나도 없는 동안은 배치를 켤 이유가 없다.
 * 기본값이 예고 없이 바뀌면 아무 값도 내지 못하면서 몇 초마다 빈 질의만 실행한다.
 */
@IntegrationTest
class ForecastScheduleDefaultTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 기본_설정은_예보_배치를_꺼둔다() {
        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(applicationContext.getBeanNamesForType(ForecastPipeline.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(PipelineForecastTrigger.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(ShadowScoringCycle.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(ShadowScoringJob.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(PipelineShutdown.class)).isEmpty();
        }
    }

    @Test
    void 예보를_끄면_예보_설정을_바인딩하지_않는다() {
        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(applicationContext.getBeanNamesForType(ForecastProperties.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(ForecastPolicy.class)).isEmpty();
        }
    }

    @Test
    void 기본_설정은_새_예보_경로의_파티션_유지_작업을_등록하지_않는다() {
        // when
        List<String> registeredTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class)
            .values()
            .stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(applicationContext.getBeanNamesForType(PipelinePartitionJob.class)).isEmpty();
            softly.assertThat(applicationContext.getBeanNamesForType(ForecastPipelineProperties.class)).isEmpty();
            softly.assertThat(registeredTasks).noneMatch(task -> task.contains("PipelinePartitionJob"));
        }
    }

    @Test
    void 폐기한_통계와_당일_보정_스케줄은_등록하지_않는다() {
        assertThat(applicationContext.containsBean("stopDemandPipelineJob")).isFalse();
        assertThat(applicationContext.containsBean("sameDayOutcomesInitializationJob")).isFalse();
    }
}
