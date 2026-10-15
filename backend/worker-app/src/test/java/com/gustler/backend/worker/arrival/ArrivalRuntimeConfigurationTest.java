package com.gustler.backend.worker.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class ArrivalRuntimeConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(ArrivalRuntimeConfiguration.class, Dependencies.class)
        .withPropertyValues("gbis.service-key=fake-test-key", "gbis.base-url=http://127.0.0.1:1");

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean JdbcClient jdbcClient() { return mock(JdbcClient.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Test
    void 기본값은_수집기를_만들지_않는다() {
        context.withPropertyValues("arrivals.enabled=false").run(application ->
            assertThat(application).doesNotHaveBean(RefreshStationArrivals.class));
    }

    @Test
    void 활성화하면_설정파일과_독립_실행기를_연결한다() {
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=local-test-token")
            .run(application -> {
                assertThat(application).hasNotFailed().hasSingleBean(RefreshStationArrivals.class);
                var executor = application.getBean("arrivalExecutor", ThreadPoolExecutor.class);
                assertThat(executor.getMaximumPoolSize()).isEqualTo(1);
                assertThat(executor.getQueue().remainingCapacity()).isEqualTo(8);
                assertThat(executor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
                assertThat(application.getBean(ArrivalProperties.class).toString()).doesNotContain("local-test-token");
            });
    }

    @Test
    void 내부_인증값이_없으면_활성화하지_않는다() {
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=")
            .run(application -> assertThat(application).hasFailed());
    }
}
