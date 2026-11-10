package com.gustler.backend.api.push.domain;

import java.util.Locale;

public record DeviceToken(String value) {
    public DeviceToken {
        if (value == null || !value.matches("[0-9a-fA-F]{1,200}")) {
            throw new IllegalArgumentException("기기 토큰 형식이 올바르지 않습니다.");
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "DeviceToken[redacted]";
    }
}
