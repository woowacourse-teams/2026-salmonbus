package com.gustler.backend.gbis.api;

import java.util.List;

public record GbisClientOptions(String baseUrl, List<GbisKey> keys) {

    private static final String UNKNOWN_SLOT = "설정에 없는 GBIS 키 슬롯이다: ";

    public GbisClientOptions {
        if (baseUrl == null || baseUrl.isBlank() || keys == null || keys.isEmpty()
            || keys.stream().anyMatch(key -> key.serviceKey() == null || key.serviceKey().isBlank())) {
            throw new IllegalArgumentException("GBIS 주소와 인증키가 필요하다");
        }
        keys = List.copyOf(keys);
    }

    public GbisClientOptions(String baseUrl, String serviceKey) {
        this(baseUrl, List.of(new GbisKey(GbisKey.PRIMARY, serviceKey)));
    }

    public String serviceKeyOf(
        String keyAlias
    ) {
        return keys.stream()
            .filter(key -> key.alias().equals(keyAlias))
            .map(GbisKey::serviceKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(UNKNOWN_SLOT + keyAlias));
    }

    @Override
    public String toString() {
        return "GbisClientOptions[baseUrl=%s, serviceKey=***]".formatted(baseUrl);
    }
}
