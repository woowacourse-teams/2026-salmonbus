package com.gustler.backend.worker.configuration;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.routecatalog.configuration.RouteCatalogConfiguration;
import com.gustler.backend.observations.configuration.ObservationsConfiguration;
import com.gustler.backend.forecasting.configuration.ForecastingConfiguration;
import com.gustler.backend.quota.configuration.CallQuotaConfiguration;
import com.gustler.backend.gbis.configuration.GbisConfiguration;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ClockConfig.class, RouteCatalogConfiguration.class, ObservationsConfiguration.class,
    ForecastingConfiguration.class, CallQuotaConfiguration.class, GbisConfiguration.class})
@EnableConfigurationProperties({GbisProperties.class, ForecastProperties.class})
public class BusinessConfiguration {
    @Bean
    GbisClientOptions gbisClientOptions(GbisProperties properties) {
        return new GbisClientOptions(properties.baseUrl(), properties.serviceKey());
    }

    @Bean
    CallQuotaPolicy callQuotaPolicy(GbisProperties properties) {
        return new CallQuotaPolicy(properties.dailyLimit());
    }

    @Bean
    ForecastPolicy forecastPolicy(ForecastProperties properties) {
        return new ForecastPolicy(properties.staleness(), properties.batchLimit(), properties.pendingLimit(),
            properties.arrivalLimit());
    }
}
