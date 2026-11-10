package com.gustler.backend.api.push.domain;

public enum PushEnvironment {
    SANDBOX,
    PRODUCTION;

    public static PushEnvironment from(String value) {
        for (PushEnvironment environment : values()) {
            if (environment.name().equals(value)) {
                return environment;
            }
        }
        throw new IllegalArgumentException("푸시 환경이 올바르지 않습니다.");
    }
}
