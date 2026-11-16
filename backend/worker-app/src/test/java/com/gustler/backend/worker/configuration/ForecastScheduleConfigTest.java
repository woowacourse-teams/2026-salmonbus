package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.worker.scheduling.ForecastTrigger;
import com.gustler.backend.worker.scheduling.PartitionJob;
import com.gustler.backend.worker.scheduling.ScoringJob;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "forecast.enabled=true")
class ForecastScheduleConfigTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 예보를_켜는_것만으로_예보와_채점과_파티션_유지_구성이_선다() {
        // then
        assertSoftly(softly -> {
            softly.assertThat(applicationContext.getBeanNamesForType(RunForecasts.class)).hasSize(1);
            softly.assertThat(applicationContext.getBeanNamesForType(ForecastLanes.class)).hasSize(1);
            softly.assertThat(applicationContext.getBeanNamesForType(ScoringCycle.class)).hasSize(1);
            softly.assertThat(applicationContext.getBeanNamesForType(ScoringFeed.class)).hasSize(1);
            softly.assertThat(applicationContext.getBeanNamesForType(ScoringJob.class)).hasSize(1);
            softly.assertThat(applicationContext.getBeanNamesForType(PartitionJob.class)).hasSize(1);
        });
    }

    @Test
    void 예보_스위치를_켜면_배치_주기_설정이_같이_바인딩된다() {
        // when
        ForecastProperties actual = applicationContext.getBean(ForecastProperties.class);

        // then
        assertThat(actual.interval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 예보를_켜도_편도_조사_스케줄을_등록하지_않는다() {
        // when
        List<String> registeredTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class)
            .values()
            .stream()
            .flatMap(holder -> holder.getScheduledTasks()
                .stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        assertThat(registeredTasks).noneMatch(task -> task.endsWith("TripQualityInvestigationJob.investigate"));
    }

    @Test
    void 예보_스위치를_켜면_30초_회차를_등록하고_관측_저장_이벤트를_받는다() {
        // when
        List<String> registeredTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class)
            .values()
            .stream()
            .flatMap(holder -> holder.getScheduledTasks()
                .stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        assertThat(registeredTasks).contains(ForecastTrigger.class.getName() + ".pickUp");
        assertThat(applicationContext.getBeansOfType(ObservationDeliveryHook.class).values())
            .anySatisfy(hook -> assertThat(AopUtils.getTargetClass(hook))
                .isEqualTo(ForecastTrigger.class));
    }
}
