package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.configuration.ForecastingModelConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 예보 런타임이 사용할 업무 구성과 실행 의존성을 Worker 앱에 연결한다.
 */
@Configuration
@Import(ForecastingModelConfiguration.class)
@EnableConfigurationProperties(ModelBundleProperties.class)
public class ForecastRuntimeConfig {
}
