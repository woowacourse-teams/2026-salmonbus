package com.gustler.backend.collector;

public record GbisKey(
    String alias,
    String serviceKey
) {

    public static final String PRIMARY = "a";

    @Override
    public String toString() {
        return "GbisKey[alias=%s, serviceKey=***]".formatted(alias);
    }
}
