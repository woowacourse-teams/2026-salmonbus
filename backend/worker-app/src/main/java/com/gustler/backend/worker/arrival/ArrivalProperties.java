package com.gustler.backend.worker.arrival;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 도착정보의 내부 인증·키별 예산·대기·보존 정책을 바인딩한다.
 * 시간 값은 Duration으로 전달하고 인증값은 문자열 표현에서 숨긴다.
 */
@ConfigurationProperties("arrivals")
public record ArrivalProperties(String internalToken, int keyDailyLimit, int queueCapacity,
    Duration refreshInterval, Duration validity, Duration retryBase, Duration retryMaximum,
    Duration startupDelay, Duration queueWait, Duration retention, Map<String, Integer> limits) {
    @ConstructorBinding
    public ArrivalProperties {
        if (!hasValidToken(internalToken) || !hasValidQueueCapacity(queueCapacity)) {
            throw invalidProperties();
        }
        if (keyDailyLimit < 1 || !hasValidRetention(retention)) {
            throw invalidProperties();
        }

        limits = positiveLimits(limits);
    }

    private static boolean hasValidToken(String internalToken) {
        return internalToken != null && !internalToken.isBlank();
    }

    private static boolean hasValidQueueCapacity(int queueCapacity) {
        return queueCapacity >= 1 && queueCapacity <= 100;
    }

    private static boolean hasValidRetention(Duration retention) {
        return retention != null && retention.compareTo(Duration.ofMinutes(1)) >= 0;
    }

    private static IllegalArgumentException invalidProperties() {
        return new IllegalArgumentException("도착 수집 설정과 내부 인증 토큰을 확인해야 한다");
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

    public ArrivalProperties(
        String internalToken,
        final int keyDailyLimit,
        final int queueCapacity,
        Duration refreshInterval,
        Duration validity,
        Duration retryBase,
        Duration retryMaximum,
        Duration startupDelay,
        Duration queueWait,
        Duration retention
    ) {
        this(internalToken, keyDailyLimit, queueCapacity, refreshInterval, validity, retryBase,
            retryMaximum, startupDelay, queueWait, retention, Map.of());
    }

    public int keyDailyLimitOf(String alias) {
        return limits.getOrDefault(alias, keyDailyLimit);
    }

    @Override
    public String toString() {
        return "ArrivalProperties[internalToken=***, keyDailyLimit=" + keyDailyLimit + "]";
    }
}
