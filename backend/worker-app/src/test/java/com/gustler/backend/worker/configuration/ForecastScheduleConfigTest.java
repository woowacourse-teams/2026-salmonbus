package com.gustler.backend.worker.configuration;

import com.gustler.backend.worker.scheduling.ArrivalLabelJob;
import com.gustler.backend.worker.scheduling.TripQualityInvestigationJob;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "forecast.enabled=true")
class ForecastScheduleConfigTest {

    /**
     * 예보 행이 하루에 얼마나 들어오나. 노선당 98만 행이 에픽이 잡은 상한이고 지금 노선이 둘이다.
     * 회수가 이보다 느리면 안 닫힌 행이 날마다 쌓인다.
     */
    private static final long LARGEST_DAILY_FORECAST_ROWS = 980_000L * 2;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 예보_스위치를_켜면_라벨_회수_배치가_선다() {
        // when
        ArrivalLabelJob actual = applicationContext.getBeanProvider(ArrivalLabelJob.class).getIfAvailable();

        // then
        assertThat(actual).isNotNull();
    }

    @Test
    void 하루_회수량이_예상_최대_유입보다_많다() {
        // given
        ForecastProperties properties = applicationContext.getBean(ForecastProperties.class);

        // when
        final long actual = Duration.ofDays(1).dividedBy(properties.settlementInterval())
            * properties.pendingLimit();

        // then
        assertThat(actual).isGreaterThan(LARGEST_DAILY_FORECAST_ROWS);
    }

    @Test
    void 예보_스위치를_켜면_배치_주기_설정이_같이_바인딩된다() {
        // when
        ForecastProperties actual = applicationContext.getBean(ForecastProperties.class);

        // then
        assertThat(actual.settlementInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 수집을_꺼도_예보를_켜면_이상_차량의_편도_조사를_등록한다() {
        // when
        List<String> registeredTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        assertThat(registeredTasks).contains(TripQualityInvestigationJob.class.getName() + ".investigate");
    }
}
