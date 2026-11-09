package com.gustler.backend.stations.configuration;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.stations.api.FillStationTiles;
import com.gustler.backend.stations.api.RequestStationTileRefresh;
import com.gustler.backend.stations.api.StationFillPolicy;
import com.gustler.backend.stations.application.StationTileFiller;
import com.gustler.backend.stations.domain.StationAroundSource;
import com.gustler.backend.stations.domain.StationTileStore;
import com.gustler.backend.stations.infrastructure.gbis.GbisStationAroundSource;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationRefreshQueue;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationTileStore;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 업무 구현과 어댑터 조립은 모듈 내부에서 담당한다. Worker는 공개 기능만 실행한다.
 */
@Configuration(proxyBeanMethods = false)
public class StationsConfiguration {

    @Bean
    RequestStationTileRefresh requestStationTileRefresh(
        JdbcClient jdbc,
        PlatformTransactionManager manager,
        Clock clock,
        StationFillPolicy policy,
        @Value("${stations.fill.queue-capacity:64}") final int capacity
    ) {
        return new JdbcStationRefreshQueue(jdbc, manager, clock, policy.refreshInterval(), capacity);
    }

    @Bean
    StationTileStore stationTileStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        return new JdbcStationTileStore(jdbc, manager);
    }

    @Bean
    StationAroundSource stationAroundSource(
        @Qualifier("stationGbisOptions") GbisClientOptions options,
        ObjectMapper json
    ) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient rest = RestClient.builder().baseUrl(options.baseUrl()).requestFactory(factory).build();
        return new GbisStationAroundSource(new GbisApiCaller(rest, options, json), json);
    }

    @Bean
    FillStationTiles fillStationTiles(
        StationTileStore store,
        StationAroundSource source,
        StationFillPolicy policy,
        Clock clock,
        StationCallQuota quota
    ) {
        return new StationTileFiller(store, source, policy, clock, quota);
    }
}
