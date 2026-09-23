package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.application.evaluation.EvaluateForecastsService;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 계수 번들이 없어 도는 배포가 하나도 없는 동안은 배치를 켤 이유가 없다.
 * 기본값이 조용히 뒤집히면 아무 값도 못 내면서 몇 초마다 빈 질의만 돈다.
 */
@IntegrationTest
class ForecastScheduleDefaultTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 기본_설정은_예보_배치를_꺼둔다() {
        // when
        EvaluateForecastsService actual = applicationContext.getBeanProvider(EvaluateForecastsService.class).getIfAvailable();

        // then
        assertThat(actual).isNull();
    }
}
