package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.configuration.ArrivalsConfiguration;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.configuration.ArrivalQuotaConfiguration;
import com.gustler.backend.worker.configuration.GbisProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ArrivalProperties.class, GbisProperties.class})
@Import({ArrivalsConfiguration.class, ArrivalQuotaConfiguration.class})
public class ArrivalRuntimeConfiguration {
    @Bean
    ArrivalQuotaPolicy arrivalQuotaPolicy(GbisProperties keys, ArrivalProperties properties) {
        return new ArrivalQuotaPolicy(properties.dailyLimit(), keys.keys().stream()
            .collect(Collectors.toMap(key -> key.alias(), key -> properties.keyDailyLimit())));
    }

    @Bean
    GbisClientOptions arrivalGbisOptions(GbisProperties keys) {
        return new GbisClientOptions(keys.baseUrl(), keys.keys());
    }

    @Bean(destroyMethod = "close")
    ExecutorService arrivalExecutor(ArrivalProperties properties) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()),
            Thread.ofPlatform().name("arrival-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ArrivalCollectionPolicy arrivalCollectionPolicy(ArrivalProperties properties) {
        return new ArrivalCollectionPolicy(properties.refreshInterval(), properties.validity(),
            properties.retryBase(), properties.retryMaximum(), properties.startupDelay(), properties.queueWait(),
            10_000, properties.dailyLimit() / 86_400.0, properties.queueCapacity(), 2);
    }
}
