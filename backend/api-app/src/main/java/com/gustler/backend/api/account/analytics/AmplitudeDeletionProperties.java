package com.gustler.backend.api.account.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analytics.amplitude")
public record AmplitudeDeletionProperties(String apiKey, String secretKey) {
    void requireComplete() {
        if (apiKey == null || apiKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Amplitude 삭제를 켜려면 AMPLITUDE_API_KEY와 AMPLITUDE_SECRET_KEY가 필요하다");
        }
    }

    @Override
    public String toString() {
        return "AmplitudeDeletionProperties[credentials=REDACTED]";
    }
}
