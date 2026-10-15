package com.gustler.backend.api.arrival.configuration;

import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.api.arrival.infrastructure.http.WorkerArrivalRefreshClient;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArrivalApiConfiguration.Properties.class)
public class ArrivalApiConfiguration {
    @ConfigurationProperties("arrivals")
    public record Properties(boolean enabled, URI workerBaseUrl, String internalToken) {
        public Properties {
            if (enabled && (workerBaseUrl == null || workerBaseUrl.getHost() == null
                || !("http".equals(workerBaseUrl.getScheme()) || "https".equals(workerBaseUrl.getScheme()))
                || workerBaseUrl.getUserInfo() != null || workerBaseUrl.getQuery() != null
                || workerBaseUrl.getFragment() != null || internalToken == null || internalToken.isBlank())) {
                throw new IllegalArgumentException("도착 갱신 Worker 주소와 내부 인증 토큰을 확인해야 한다");
            }
        }
        @Override
        public String toString() { return "ArrivalApiProperties[enabled=" + enabled + ", internalToken=***]"; }
    }

    @Bean
    ArrivalRefreshGateway arrivalRefreshGateway(Properties properties, Clock clock) {
        if (!properties.enabled()) return station -> new ArrivalRefresh(ArrivalRefresh.Status.UNAVAILABLE,
            "REFRESH_DISABLED", clock.instant().plusSeconds(40));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient rest = RestClient.builder().baseUrl(properties.workerBaseUrl().toString())
            .requestFactory(factory).build();
        return new WorkerArrivalRefreshClient(rest, properties.internalToken(), clock);
    }
}
