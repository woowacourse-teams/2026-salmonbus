package com.gustler.backend.arrivals.configuration;

import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import com.gustler.backend.arrivals.api.RetainArrivalSnapshots;
import com.gustler.backend.arrivals.application.ArrivalRefreshCoordinator;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import com.gustler.backend.arrivals.infrastructure.gbis.GbisArrivalSource;
import com.gustler.backend.arrivals.infrastructure.jdbc.JdbcArrivalStore;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.api.ArrivalCallQuota;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** 업무 구현과 어댑터 조립은 모듈 내부에서 담당한다. Worker는 공개 유스케이스만 실행한다. */
@Configuration(proxyBeanMethods = false)
public class ArrivalsConfiguration {
    @Bean
    ArrivalStore arrivalStore(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcArrivalStore(jdbc, json);
    }

    @Bean
    ArrivalSource arrivalSource(@Qualifier("arrivalGbisOptions") GbisClientOptions options,
        ObjectMapper json, Clock clock) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient rest = RestClient.builder().baseUrl(options.baseUrl()).requestFactory(factory).build();
        return new GbisArrivalSource(new GbisApiCaller(rest, options, json), json, clock);
    }

    @Bean
    RefreshStationArrivals refreshStationArrivals(
        ArrivalStore store,
        ArrivalSource source,
        ArrivalCallQuota quota,
        @Qualifier("arrivalExecutor") Executor executor,
        ArrivalCollectionPolicy policy,
        Clock clock
    ) {
        return new ArrivalRefreshCoordinator(store, source, quota, executor, policy, clock, System::nanoTime);
    }

    @Bean
    RetainArrivalSnapshots retainArrivalSnapshots(ArrivalStore store) {
        return store::purgeExpiredBefore;
    }
}
