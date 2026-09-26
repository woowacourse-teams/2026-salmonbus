package com.gustler.backend.worker.configuration;

import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.configuration.GbisConfiguration;
import com.gustler.backend.observations.configuration.ObservationsConfiguration;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.configuration.CallQuotaConfiguration;
import com.gustler.backend.routecatalog.configuration.RouteCatalogConfiguration;
import com.gustler.backend.worker.scheduling.CollectionScheduler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 수집을 실행하는 진입점에서 명시적으로 가져오는 구성. 자체적으로 스케줄을 등록하지 않는다. */
@Import({RouteCatalogConfiguration.class, ObservationsConfiguration.class, CallQuotaConfiguration.class,
    GbisConfiguration.class, CollectionScheduler.class})
@EnableConfigurationProperties(GbisProperties.class)
public class CollectionRuntimeConfiguration {

    @Bean
    GbisClientOptions gbisClientOptions(GbisProperties properties) {
        return new GbisClientOptions(properties.baseUrl(), properties.serviceKey());
    }

    @Bean
    CallQuotaPolicy callQuotaPolicy(GbisProperties properties) {
        return CallQuotaPolicy.sameForEveryApi(properties.dailyLimit());
    }
}
