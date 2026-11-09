package com.gustler.backend.worker.configuration;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.evaluation.SameDayInitializationPolicy;
import com.gustler.backend.forecasting.api.statistics.DemandStatisticsPolicy;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.observations.configuration.ObservationPersistenceConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Worker 설정을 업무 정책으로 변환하고 필수 정책 설정의 출처와 값을 확인한다.
 */
@Configuration
@Import({ClockConfig.class, ObservationPersistenceConfiguration.class, ForecastingConfiguration.class})
public class BusinessConfiguration {
    private static final String STALENESS = "forecast.staleness";

    @Bean
    DemandStatisticsPolicy demandStatisticsPolicy(@Value("${forecast.statistics-interval:6h}") String interval) {
        return new DemandStatisticsPolicy(DurationStyle.detectAndParse(interval));
    }

    /**
     * 신선도 창을 환경변수로 바꾸지 못하게 막는다.
     *
     * <p>이 창은 api-app의 BoardFreshnessPolicy가 약속한 창 이상이어야 한다. 두 앱은 다른 프로세스여서
     * 한쪽만 바꿔도 각각 기동에는 성공하지만, 발행이 지연됐다가 재개될 때 보드가 선택할 최신 발행이
     * 없어 503을 반환한다. 설정 파일 값은 계약 테스트에서 보드의 창과 대조하지만 환경변수는 이 검사를
     * 거치지 않는다. 그래서 값을 조용히 무시하지 않고 기동을 멈춘다.
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties({ForecastProperties.class, SameDayInitializationProperties.class})
    static class ForecastPolicies {
        @Bean
        ForecastPolicy forecastPolicy(
            ForecastProperties properties,
            ConfigurableEnvironment environment
        ) {
            requireStalenessFromConfigurationFile(environment);
            return new ForecastPolicy(properties.staleness(), properties.batchLimit(), properties.pendingLimit(),
                properties.arrivalLimit());
        }

        @Bean
        SameDayInitializationPolicy sameDayInitializationPolicy(SameDayInitializationProperties properties) {
            return new SameDayInitializationPolicy(properties.retryInterval(), properties.statementTimeout(),
                properties.lockTimeout());
        }
    }
}
