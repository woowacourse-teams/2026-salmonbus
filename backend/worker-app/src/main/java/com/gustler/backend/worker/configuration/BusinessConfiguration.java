package com.gustler.backend.worker.configuration;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.observations.configuration.ObservationPersistenceConfiguration;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

@Configuration
@Import({ClockConfig.class, ObservationPersistenceConfiguration.class, ForecastingConfiguration.class})
@EnableConfigurationProperties(ForecastProperties.class)
public class BusinessConfiguration {

    private static final String STALENESS = "forecast.staleness";

    @Bean
    ForecastPolicy forecastPolicy(
        ForecastProperties properties,
        ConfigurableEnvironment environment
    ) {
        requireStalenessFromConfigurationFile(environment);
        return new ForecastPolicy(properties.staleness(), properties.batchLimit(), properties.pendingLimit(),
            properties.arrivalLimit());
    }

    /**
     * 신선도 창을 환경변수로 바꾸지 못하게 막는다.
     *
     * <p>이 창은 api-app의 BoardFreshnessPolicy가 약속한 창 이상이어야 한다. 둘은 다른 프로세스라
     * 한쪽만 바꾸면 기동이 따로 성공하고, 그 뒤 발행이 밀렸다 돌아올 때 보드가 집을 최신 발행이
     * 없어 503이 나간다. 설정 파일 값은 계약 테스트가 보드의 창과 대조하지만 환경변수는 그 검사를
     * 지나친다. 그래서 값을 조용히 무시하지 않고 기동을 멈춘다.
     */
    static void requireStalenessFromConfigurationFile(
        ConfigurableEnvironment environment
    ) {
        PropertySource<?> systemEnvironment = environment.getPropertySources()
            .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (systemEnvironment != null && systemEnvironment.containsProperty(STALENESS)) {
            throw new IllegalStateException(
                "%s 는 환경변수로 바꾸지 않는다. FORECAST_STALENESS 를 지우고 설정 파일에서 정한다".formatted(STALENESS));
        }
    }
}
