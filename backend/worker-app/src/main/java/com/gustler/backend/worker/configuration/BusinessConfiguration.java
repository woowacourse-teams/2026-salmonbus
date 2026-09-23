package com.gustler.backend.worker.configuration;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.observations.configuration.ObservationPersistenceConfiguration;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ClockConfig.class, ObservationPersistenceConfiguration.class, ForecastingConfiguration.class})
@EnableConfigurationProperties(ForecastProperties.class)
public class BusinessConfiguration {

    @Bean
    ForecastPolicy forecastPolicy(ForecastProperties properties) {
        return new ForecastPolicy(properties.staleness(), properties.batchLimit(), properties.pendingLimit(),
            properties.arrivalLimit());
    }
}
