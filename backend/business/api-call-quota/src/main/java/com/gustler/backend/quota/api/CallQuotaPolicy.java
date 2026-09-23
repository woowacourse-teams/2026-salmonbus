package com.gustler.backend.quota.api;

public record CallQuotaPolicy(int dailyLimit) {
    public CallQuotaPolicy {
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException("하루 호출 한도는 1 이상이어야 한다");
        }
    }
}
