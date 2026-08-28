package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "forecast.enabled=true")
class ForecastScheduleConfigTest {

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
    void 예보_스위치를_켜면_배치_주기_설정이_같이_바인딩된다() {
        // when
        ForecastProperties actual = applicationContext.getBean(ForecastProperties.class);

        // then
        assertThat(actual.settlementInterval()).isEqualTo(Duration.ofSeconds(60));
    }
}
