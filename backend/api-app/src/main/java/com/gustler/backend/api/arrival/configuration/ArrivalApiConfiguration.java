package com.gustler.backend.api.arrival.configuration;

import com.gustler.backend.api.arrival.application.ArrivalFirstResultPolicy;
import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.api.arrival.configuration.ArrivalApiConfiguration.Properties;
import com.gustler.backend.api.arrival.infrastructure.http.WorkerArrivalRefreshClient;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 도착정보 조회의 첫 결과 대기 정책과 내부 Worker HTTP 클라이언트를 연결한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Properties.class)
public class ArrivalApiConfiguration {
    @ConfigurationProperties("arrivals")
    public record Properties(boolean enabled, URI workerBaseUrl, String internalToken) {
        public Properties {
            if (enabled && hasInvalidWorkerConfiguration(workerBaseUrl, internalToken)) {
                throw new IllegalArgumentException("도착 갱신 Worker 주소와 내부 인증 토큰을 확인해야 한다");
            }
        }

        private static boolean hasInvalidWorkerConfiguration(URI workerBaseUrl, String internalToken) {
            if (!hasSupportedAddress(workerBaseUrl)) {
                return true;
            }
            if (hasForbiddenAddressComponent(workerBaseUrl)) {
                return true;
            }
            return internalToken == null || internalToken.isBlank();
        }

        private static boolean hasSupportedAddress(URI workerBaseUrl) {
            if (workerBaseUrl == null || workerBaseUrl.getHost() == null) {
                return false;
            }
            String scheme = workerBaseUrl.getScheme();
            return "http".equals(scheme) || "https".equals(scheme);
        }

        private static boolean hasForbiddenAddressComponent(URI workerBaseUrl) {
            if (workerBaseUrl.getUserInfo() != null || workerBaseUrl.getQuery() != null) {
                return true;
            }
            return workerBaseUrl.getFragment() != null;
        }

        @Override
        public String toString() {
            return "ArrivalApiProperties[enabled=" + enabled + ", internalToken=***]";
        }
    }

    @Bean
    ArrivalFirstResultPolicy arrivalFirstResultPolicy(
        @Value("${arrivals.first-result-wait:500ms}") Duration maximumWait,
        @Value("${arrivals.first-result-max-waiters:32}") final int maximumWaiters) {
        return new ArrivalFirstResultPolicy(maximumWait, maximumWaiters);
    }

    @Bean
    ArrivalRefreshGateway arrivalRefreshGateway(
        Properties properties,
        Clock clock,
        ArrivalFirstResultPolicy firstResult
    ) {
        if (!properties.enabled()) {
            return station -> new ArrivalRefresh(Status.UNAVAILABLE,
                "REFRESH_DISABLED", clock.instant().plusSeconds(40));
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build();
        RestClient immediate = refreshClient(client, properties, Duration.ofMillis(500));
        RestClient waiting = refreshClient(client, properties, Duration.ofMillis(500).plus(firstResult.maximumWait()));
        return new WorkerArrivalRefreshClient(immediate, waiting, properties.internalToken(), clock);
    }

    private RestClient refreshClient(HttpClient client, Properties properties, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(properties.workerBaseUrl().toString()).requestFactory(factory).build();
    }
}
