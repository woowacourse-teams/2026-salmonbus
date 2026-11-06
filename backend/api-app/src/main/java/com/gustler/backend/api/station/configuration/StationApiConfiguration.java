package com.gustler.backend.api.station.configuration;

import com.gustler.backend.api.station.application.StationRefreshGateway;
import com.gustler.backend.api.station.infrastructure.http.WorkerStationRefreshClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class StationApiConfiguration {

    @Bean
    StationRefreshGateway stationRefreshGateway(
        Clock clock,
        @Value("${stations.refresh.enabled:false}") final boolean enabled,
        @Value("${stations.refresh.worker-base-url:http://127.0.0.1:8081}") URI base,
        @Value("${stations.refresh.internal-token:}") String token
    ) {
        if (!enabled) {
            return tile -> {
            };
        }
        if (hasInvalidConfiguration(base, token)) {
            throw new IllegalArgumentException("정류장 갱신 Worker 주소와 내부 인증 토큰을 확인해야 한다");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(500));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().daemon().name("station-refresh-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
        return new WorkerStationRefreshClient(
            RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build(),
            token,
            clock,
            executor
        );
    }

    private static boolean hasInvalidConfiguration(URI base, String token) {
        if (token.isBlank() || base.getHost() == null) {
            return true;
        }
        if (!Set.of("http", "https").contains(base.getScheme())) {
            return true;
        }
        return hasForbiddenAddressComponent(base);
    }

    private static boolean hasForbiddenAddressComponent(URI base) {
        if (base.getUserInfo() != null || base.getQuery() != null) {
            return true;
        }
        return base.getFragment() != null;
    }
}
