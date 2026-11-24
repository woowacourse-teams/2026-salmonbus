package com.gustler.backend.worker.configuration;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.observations.configuration.ObservationStorageConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Worker가 쓰는 업무 모듈의 구성을 가져오고 필수 정책 설정의 출처를 확인한다.
 */
@Configuration
@Import({ClockConfig.class, ObservationStorageConfiguration.class, ForecastingConfiguration.class})
public class BusinessConfiguration {
    private static final String STALENESS = "forecast.staleness";

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
}
