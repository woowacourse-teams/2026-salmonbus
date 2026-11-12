package com.gustler.backend.api.arrival.configuration;

import com.gustler.backend.api.arrival.application.ArrivalFirstResultPolicy;
import com.gustler.backend.api.arrival.application.ArrivalRefreshDispatcher;
import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.api.arrival.configuration.ArrivalApiConfiguration.Properties;
import com.gustler.backend.api.arrival.infrastructure.http.WorkerArrivalRefreshClient;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /**
     * 갱신 시점이 지난 수신본 조회의 Worker 갱신 요청을 보낼 스레드다. 2개를 쓰고 64건까지 기다리게 하며,
     * 넘치면 실행기가 그 조회의 요청을 건너뛴다. 대기 중인 갱신 요청은 다음 조회에서 다시 보내면 되므로 종료 때 기다리지 않고 버린다.
     */
    @Bean(defaultCandidate = false, destroyMethod = "shutdownNow")
    ExecutorService arrivalRefreshExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().name("arrival-refresh-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * {@code arrivals.refresh-async}가 false이거나 Worker 갱신을 꺼 두었으면({@code arrivals.enabled=false}) 갱신 요청을
     * 예전처럼 요청 스레드에서 보낸다. 꺼 둔 게이트웨이는 Worker를 부르지 않고 바로 답하므로 비동기로 보낼 이유가 없다.
     */
    @Bean
    ArrivalRefreshDispatcher arrivalRefreshDispatcher(
        ArrivalRefreshGateway gateway,
        Properties properties,
        Clock clock,
        MeterRegistry meters,
        @Qualifier("arrivalRefreshExecutor") ExecutorService executor,
        @Value("${arrivals.refresh-async:true}") final boolean async,
        @Value("${arrivals.refresh-async-max-stations:4096}") final int maximumStations
    ) {
        if (!async || !properties.enabled()) {
            return ArrivalRefreshDispatcher.synchronous(gateway, clock, meters);
        }
        return ArrivalRefreshDispatcher.async(gateway, executor, clock, maximumStations, meters);
    }

    private RestClient refreshClient(HttpClient client, Properties properties, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(properties.workerBaseUrl().toString()).requestFactory(factory).build();
    }
}
