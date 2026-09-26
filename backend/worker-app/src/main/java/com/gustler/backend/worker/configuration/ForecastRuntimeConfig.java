package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.configuration.ForecastingModelConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(ForecastingModelConfiguration.class)
@EnableConfigurationProperties(ModelBundleProperties.class)
public class ForecastRuntimeConfig {
}
