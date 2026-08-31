package com.gustler.backend.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gbis")
public record GbisProperties(
    String baseUrl,
    String serviceKey,
    int dailyLimit
) {

    public GbisProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("gbis.base-url must not be blank");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                "gbis.service-key must not be blank. Set the GBIS_SERVICE_KEY environment variable");
        }
        if (dailyLimit <= 0) {
            throw new IllegalStateException("gbis.daily-limit must be positive");
        }
    }

    @Override
    public String toString() {
        return "GbisProperties[baseUrl=%s, serviceKey=***, dailyLimit=%d]".formatted(baseUrl, dailyLimit);
    }
}
