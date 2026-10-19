package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.configuration.ArrivalsConfiguration;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.configuration.ArrivalQuotaConfiguration;
import com.gustler.backend.worker.configuration.GbisProperties;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ArrivalRuntimeConfiguration.class);

    @Bean
    ArrivalQuotaPolicy arrivalQuotaPolicy(GbisProperties keys, ArrivalProperties properties) {
        var aliases = keys.serviceKeys().stream().map(key -> key.alias()).toList();
        properties.limits().keySet().stream().filter(alias -> !aliases.contains(alias)).sorted().findFirst()
            .ifPresent(alias -> {
                throw new IllegalStateException("arrivals.limits." + alias + " 에 맞는 GBIS 키가 없다. 별칭을 확인하라");
            });
        Map<String, Integer> keyLimits = aliases.stream()
            .collect(Collectors.toMap(alias -> alias, properties::keyDailyLimitOf));
        final int dailyLimit = Math.toIntExact(keyLimits.values().stream().mapToLong(Integer::longValue).sum());
        log.info("도착정보 키 {}개로 수집한다. 별칭={} 하루 한도 합={} 초당 호출={}",
            aliases.size(), String.join(",", aliases), dailyLimit, String.format("%.3f", dailyLimit / 86_400.0));
        return new ArrivalQuotaPolicy(dailyLimit, keyLimits);
    }

    @Bean
    GbisClientOptions arrivalGbisOptions(GbisProperties keys) {
        return new GbisClientOptions(keys.baseUrl(), keys.serviceKeys());
    }

    @Bean(destroyMethod = "close")
    ExecutorService arrivalExecutor(ArrivalProperties properties) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()),
            Thread.ofPlatform().name("arrival-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ArrivalCollectionPolicy arrivalCollectionPolicy(ArrivalProperties properties, ArrivalQuotaPolicy quota) {
        return new ArrivalCollectionPolicy(properties.refreshInterval(), properties.validity(),
            properties.retryBase(), properties.retryMaximum(), properties.startupDelay(), properties.queueWait(),
            10_000, quota.dailyLimit() / 86_400.0, properties.queueCapacity(), 2);
    }
}
