package com.gustler.backend.worker.arrival;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("arrivals")
public record ArrivalProperties(String internalToken, int keyDailyLimit, int queueCapacity,
    Duration refreshInterval, Duration validity, Duration retryBase, Duration retryMaximum,
    Duration startupDelay, Duration queueWait, Duration retention, Map<String, Integer> limits) {
    @ConstructorBinding
    public ArrivalProperties {
        if (internalToken == null || internalToken.isBlank() || queueCapacity < 1 || queueCapacity > 100
            || keyDailyLimit < 1
            || retention == null || retention.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("도착 수집 설정과 내부 인증 토큰을 확인해야 한다");
        }
        limits = positiveLimits(limits);
    }

    private static Map<String, Integer> positiveLimits(Map<String, Integer> limits) {
        if (limits == null) {
            return Map.of();
        }
        limits.forEach((alias, limit) -> {
            if (limit == null || limit < 1) {
                throw new IllegalArgumentException("arrivals.limits." + alias + " 는 1 이상이어야 한다");
            }
        });
        return Map.copyOf(limits);
    }

    public ArrivalProperties(String internalToken, int keyDailyLimit, int queueCapacity,
        Duration refreshInterval, Duration validity, Duration retryBase, Duration retryMaximum,
        Duration startupDelay, Duration queueWait, Duration retention) {
        this(internalToken, keyDailyLimit, queueCapacity, refreshInterval, validity, retryBase,
            retryMaximum, startupDelay, queueWait, retention, Map.of());
    }

    public int keyDailyLimitOf(String alias) {
        return limits.getOrDefault(alias, keyDailyLimit);
    }

    @Override
    public String toString() { return "ArrivalProperties[internalToken=***, keyDailyLimit=" + keyDailyLimit + "]"; }
}
