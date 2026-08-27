package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.GbisLocationResult.NoResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = {GbisClientConfig.class, GbisLocationSource.class})
@ImportAutoConfiguration({
    HttpClientAutoConfiguration.class,
    ImperativeHttpClientAutoConfiguration.class,
    RestClientAutoConfiguration.class,
    JacksonAutoConfiguration.class,
})
class GbisLocationSourceTimeoutTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration RESPONSE_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_ACCEPTABLE_WAIT = Duration.ofSeconds(3);
    private static final String ROUTE_3330 = "204000057";
    private static final String FAKE_SERVICE_KEY = "fake-service-key-for-test";

    private static final HttpServer SLOW_OPEN_API = startSlowOpenApi();

    @Autowired
    private GbisLocationSource source;

    @DynamicPropertySource
    static void pointAtSlowOpenApi(
        DynamicPropertyRegistry registry
    ) {
        registry.add("gbis.base-url",
            () -> "http://localhost:" + SLOW_OPEN_API.getAddress().getPort());
        registry.add("spring.http.clients.read-timeout", READ_TIMEOUT::toString);
    }

    @Test
    void Open_API가_응답을_안_줘도_읽기_제한시간에_포기한다() {
        // when
        long startedAt = System.nanoTime();
        GbisLocationResult actual = source.read(ROUTE_3330);
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        // then
        assertThat(actual).isInstanceOf(NoResponse.class);
        assertThat(waited).isLessThan(MAX_ACCEPTABLE_WAIT);
    }

    @Test
    void 포기한_응답_사유에_서비스키가_남지_않는다() {
        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(NoResponse.class,
            noResponse -> assertThat(noResponse.message()).doesNotContain(FAKE_SERVICE_KEY));
    }

    private static HttpServer startSlowOpenApi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(RESPONSE_DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
