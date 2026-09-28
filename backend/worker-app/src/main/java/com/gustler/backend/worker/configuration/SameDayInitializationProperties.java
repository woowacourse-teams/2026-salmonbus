package com.gustler.backend.worker.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("forecast.same-day-initialization")
public record SameDayInitializationProperties(
    @DefaultValue("10s") Duration interval,
    @DefaultValue("60s") Duration retryInterval,
    @DefaultValue("25s") Duration statementTimeout,
    @DefaultValue("100ms") Duration lockTimeout
) {
    public SameDayInitializationProperties {
        requirePositive("interval", interval);
        requirePositive("retry-interval", retryInterval);
        requirePositive("statement-timeout", statementTimeout);
        requirePositive("lock-timeout", lockTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        // PostgreSQL의 0ms는 제한 해제이므로 1ms 미만도 허용하지 않는다.
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("forecast.same-day-initialization." + name + " must be at least 1ms");
        }
    }
}
