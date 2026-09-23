package com.gustler.backend.forecasting.support;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.observations.configuration.ObservationPersistenceConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Worker를 기동하지 않고 예보 업무와 실제 DB 트랜잭션을 검증한다. 각 모듈이 공개한 구성을 그대로 가져온다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({PostgresTestContainer.class, ClockConfig.class, ForecastingConfiguration.class,
    ObservationPersistenceConfiguration.class})
class ForecastingTestConfiguration {

    @Bean
    ForecastPolicy forecastPolicy(
        @Value("${forecast.staleness}") Duration staleness,
        @Value("${forecast.batch-limit}") int batchLimit,
        @Value("${forecast.pending-limit}") int pendingLimit,
        @Value("${forecast.arrival-limit}") int arrivalLimit
    ) {
        return new ForecastPolicy(staleness, batchLimit, pendingLimit, arrivalLimit);
    }
}
