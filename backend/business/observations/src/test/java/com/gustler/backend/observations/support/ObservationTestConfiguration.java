package com.gustler.backend.observations.support;

import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.configuration.GbisConfiguration;
import com.gustler.backend.observations.application.CollectionBatchService;
import com.gustler.backend.observations.application.ObservationCollector;
import com.gustler.backend.observations.configuration.ObservationStorageConfiguration;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationClient;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.configuration.CallQuotaConfiguration;
import com.gustler.backend.routecatalog.configuration.RouteCatalogConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Worker를 기동하지 않고 수집 업무와 실제 DB 트랜잭션에 필요한 구성 요소를 직접 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({
    PostgresTestContainer.class,
    ClockConfig.class,
    RouteCatalogConfiguration.class,
    ObservationStorageConfiguration.class,
    GbisObservationClient.class,
    ObservationCollector.class,
    CollectionBatchService.class,
    CallQuotaConfiguration.class,
    GbisConfiguration.class
})
class ObservationTestConfiguration {
    @Bean
    CallQuotaPolicy callQuotaPolicy() {
        return CallQuotaPolicy.sameForEveryApi(10_000);
    }

    @Bean
    GbisClientOptions gbisClientOptions() {
        return new GbisClientOptions("http://localhost:1", "test-service-key");
    }
}
