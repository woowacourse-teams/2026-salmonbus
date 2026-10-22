package com.gustler.backend.worker.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisKey;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
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
        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    void 기본값은_수집기를_만들지_않는다() {
        // when
        context.withPropertyValues("arrivals.enabled=false").run(application ->

            // then
            assertThat(application).doesNotHaveBean(RefreshStationArrivals.class));
    }

    @Test
    void 활성화하면_설정파일과_독립_실행기를_연결한다() {
        // when
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=local-test-token")
            .run(application -> {

                // then
                assertThat(application).hasNotFailed().hasSingleBean(RefreshStationArrivals.class);
                ThreadPoolExecutor executor = application.getBean("arrivalExecutor", ThreadPoolExecutor.class);
                assertThat(executor.getMaximumPoolSize()).isEqualTo(1);
                assertThat(executor.getQueue().remainingCapacity()).isEqualTo(8);
                assertThat(executor.getRejectedExecutionHandler()).isInstanceOf(AbortPolicy.class);
                assertThat(application.getBean(ArrivalProperties.class).toString()).doesNotContain("local-test-token");
            });
    }

    @Test
    void 내부_인증값이_없으면_활성화하지_않는다() {
        // when
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=")
            .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void 키를_늘리면_도착정보_전체_예산과_호출_속도도_키별_한도의_합으로_늘어난다() {
        // when
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=local-test-token",
            "gbis.keys.e=fake-key-e", "gbis.keys.f10=fake-key-f10")
            .run(application -> {

                // then
                assertThat(application).hasNotFailed();
                ArrivalQuotaPolicy policy = application.getBean(ArrivalQuotaPolicy.class);
                ArrivalProperties properties = application.getBean(ArrivalProperties.class);
                assertThat(policy.keyLimits().keySet()).contains("a", "e", "f10");
                assertThat(policy.keyLimits().values()).containsOnly(properties.keyDailyLimit());
                assertThat(policy.dailyLimit()).isEqualTo(properties.keyDailyLimit() * 3);
                ArrivalCollectionPolicy collection = application.getBean(ArrivalCollectionPolicy.class);
                assertThat(collection.callsPerSecond()).isEqualTo(policy.dailyLimit() / 86_400.0);
                GbisClientOptions options = application.getBean("arrivalGbisOptions", GbisClientOptions.class);
                assertThat(options.keys()
                    .stream()
                    .map(GbisKey::alias)
                    .toList())
                    .containsAll(policy.keyLimits().keySet());
            });
    }

    @Test
    void 도착정보_키별_한도를_주면_그_키만_그_한도로_센다() {
        // when
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=local-test-token",
            "gbis.keys.e=fake-key-e", "arrivals.limits.e=1000")
            .run(application -> {
                ArrivalQuotaPolicy policy = application.getBean(ArrivalQuotaPolicy.class);
                final int keyDailyLimit = application.getBean(ArrivalProperties.class).keyDailyLimit();

                // then
                assertThat(policy.keyLimits()).containsEntry("e", 1_000).containsEntry("a", keyDailyLimit);
                assertThat(policy.dailyLimit()).isEqualTo(keyDailyLimit + 1_000);
            });
    }

    @Test
    void 설정에_없는_별칭에_도착정보_한도를_주면_활성화하지_않는다() {
        // when
        context.withPropertyValues("arrivals.enabled=true", "arrivals.internal-token=local-test-token",
        "arrivals.limits.zz=1000")
            .run(application -> assertThat(application).hasFailed());
    }
}
