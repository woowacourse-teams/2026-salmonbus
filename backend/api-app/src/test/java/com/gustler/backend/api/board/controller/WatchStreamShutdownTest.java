package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.application.BoardBatchRead;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.route.RouteNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class WatchStreamShutdownTest {
    private static final String STREAM = "/api/v1/routes/123456789/watch/events";
    private static final Duration PROMPT_CLOSE = Duration.ofSeconds(5);

    @Test
    void 스트림이_열려_있어도_컨텍스트는_기다리지_않고_닫히고_클라이언트는_정상_종료를_본다() throws Exception {
        // given
        AnnotationConfigServletWebServerApplicationContext server = start(Map.of("watch.stream.enabled", "true"));
        try (WatchStreamClient client = WatchStreamClient.open(server.getWebServer().getPort(), STREAM)) {
            assertThat(client.status()).isEqualTo(200);
            assertThat(client.nextBlock()).startsWith("retry:");

            // when
            long started = System.nanoTime();
            server.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - started);

            // then
            assertThat(closing).isLessThan(PROMPT_CLOSE);
            assertThat(client.nextBlock()).isNull();
            assertThat(client.ended()).isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void 변경_감지기의_읽기가_돌고_있어도_컨텍스트는_기다리지_않고_닫힌다() throws Exception {
        // given
        AnnotationConfigServletWebServerApplicationContext server = start(
            Map.of("watch.stream.enabled", "true", "watch.stream.detect-interval", "20ms"));
        StalledBoards boards = server.getBean(StalledBoards.class);
        boards.stall();
        try (WatchStreamClient client = WatchStreamClient.open(server.getWebServer().getPort(), STREAM)) {
            assertThat(client.nextBlock()).startsWith("retry:");
            assertThat(boards.entered.await(PROMPT_CLOSE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // when
            long started = System.nanoTime();
            server.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - started);

            // then
            assertThat(closing).isLessThan(PROMPT_CLOSE);
            assertThat(boards.gate.getCount()).isEqualTo(1);
            assertThat(client.nextBlock()).isNull();
            assertThat(client.ended()).isTrue();
        } finally {
            boards.release();
            server.close();
        }
    }

    private static AnnotationConfigServletWebServerApplicationContext start(Map<String, Object> properties) {
        AnnotationConfigServletWebServerApplicationContext server = new AnnotationConfigServletWebServerApplicationContext();
        server.getEnvironment().getPropertySources().addFirst(new MapPropertySource("watch-stream", properties));
        server.register(GracefulTomcat.class, WatchStreamConfiguration.class, WatchStreamController.class);
        server.refresh();
        return server;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class GracefulTomcat {
        @Bean
        TomcatServletWebServerFactory tomcat() {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
            factory.setShutdown(Shutdown.GRACEFUL);
            return factory;
        }

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }

        @Bean
        MeterRegistry meters() {
            return new SimpleMeterRegistry();
        }

        @Bean
        StalledBoards boards() {
            return new StalledBoards();
        }
    }

    static final class StalledBoards extends BoardQueryService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean stalled;

        StalledBoards() {
            super(null, null, null, null);
        }

        void stall() {
            stalled = true;
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Map<String, BoardBatchRead> readBoardsWithStatementLimit(
            Collection<String> routeIds, Duration statementLimit) {
            if (stalled) {
                entered.countDown();
                try {
                    gate.await();
                } catch (InterruptedException interrupt) {
                    Thread.currentThread().interrupt();
                }
            }
            Map<String, BoardBatchRead> reads = new LinkedHashMap<>();
            for (String routeId : routeIds) {
                reads.put(routeId, BoardBatchRead.failed(new RouteNotFoundException()));
            }
            return reads;
        }
    }
}
