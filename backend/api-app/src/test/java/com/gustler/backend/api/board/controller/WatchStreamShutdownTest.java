package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 공유 시험 컨텍스트는 닫으면 다른 시험이 쓰지 못해서 닫는 시간을 잴 수 없다. 그래서 톰캣과 MVC 만 있는 컨텍스트를 직접 만든다.
 */
class WatchStreamShutdownTest {
    private static final String STREAM = "/api/v1/routes/123456789/watch/events";
    private static final Duration PROMPT_CLOSE = Duration.ofSeconds(5);

    @Test
    void 스트림이_열려_있어도_컨텍스트는_기다리지_않고_닫히고_클라이언트는_정상_종료를_본다() throws Exception {
        // given
        AnnotationConfigServletWebServerApplicationContext server = new AnnotationConfigServletWebServerApplicationContext();
        server.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("watch-stream", Map.of("watch.stream.enabled", "true")));
        server.register(GracefulTomcat.class, WatchStreamConfiguration.class, WatchStreamController.class);
        server.refresh();
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
    }
}
