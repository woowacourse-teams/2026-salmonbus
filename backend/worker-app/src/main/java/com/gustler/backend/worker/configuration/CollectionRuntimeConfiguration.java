package com.gustler.backend.worker.configuration;

import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisKey;
import com.gustler.backend.gbis.configuration.GbisConfiguration;
import com.gustler.backend.observations.configuration.ObservationsConfiguration;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.configuration.CallQuotaConfiguration;
import com.gustler.backend.routecatalog.configuration.RouteCatalogConfiguration;
import com.gustler.backend.worker.scheduling.CollectionScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 수집을 실행하는 진입점에서 명시적으로 가져오는 구성. 자체적으로 스케줄을 등록하지 않는다. */
@Import({RouteCatalogConfiguration.class, ObservationsConfiguration.class, CallQuotaConfiguration.class,
    GbisConfiguration.class, CollectionScheduler.class})
@EnableConfigurationProperties(RouteGeometryProperties.class)
public class CollectionRuntimeConfiguration {

    /** 정적 형상 HTTP 대기가 위치 관측 스케줄을 막지 않게 분리한다. */
    @Bean(destroyMethod = "close")
    ExecutorService routeGeometryExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().name("route-geometry-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @Primary
    GbisClientOptions gbisClientOptions(GbisProperties properties) {
        return new GbisClientOptions(properties.baseUrl(), properties.serviceKeys());
    }

    @Bean
    CallQuotaPolicy callQuotaPolicy(GbisProperties properties) {
        return CallQuotaPolicy.sameForEveryApi(properties.dailyLimit(),
            properties.serviceKeys().stream().map(GbisKey::alias).toList(), properties.limits());
    }
}
