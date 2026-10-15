package com.gustler.backend.worker.arrival;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("arrivals")
public record ArrivalProperties(String internalToken, int dailyLimit, int keyDailyLimit, int queueCapacity,
    Duration refreshInterval, Duration validity, Duration retryBase, Duration retryMaximum,
    Duration startupDelay, Duration queueWait, Duration retention) {
    public ArrivalProperties {
        if (internalToken == null || internalToken.isBlank() || queueCapacity < 1 || queueCapacity > 100
            || dailyLimit < 1 || dailyLimit > 40_000 || keyDailyLimit < 1 || keyDailyLimit > 10_000
            || retention == null || retention.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("도착 수집 설정과 내부 인증 토큰을 확인해야 한다");
        }
    }

    @Override
    public String toString() { return "ArrivalProperties[internalToken=***, dailyLimit=" + dailyLimit + "]"; }
}
