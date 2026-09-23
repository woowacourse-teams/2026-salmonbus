package com.gustler.backend.gbis.api;

public record GbisClientOptions(String baseUrl, String serviceKey) {
    public GbisClientOptions {
        if (baseUrl == null || baseUrl.isBlank() || serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("GBIS 주소와 인증키가 필요하다");
        }
    }

    @Override
    public String toString() {
        return "GbisClientOptions[baseUrl=%s, serviceKey=***]".formatted(baseUrl);
    }
}
