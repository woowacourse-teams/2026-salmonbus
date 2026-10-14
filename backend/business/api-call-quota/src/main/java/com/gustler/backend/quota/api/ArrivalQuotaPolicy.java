package com.gustler.backend.quota.api;

import java.util.Map;

/** 도착정보 예산은 위치/노선 수집의 설정과 독립이다. 기존 정책에 필수 설정을 추가하지 않는다. */
public record ArrivalQuotaPolicy(int dailyLimit, Map<String, Integer> keyLimits) {
    public static final String TOTAL = "__total__";

    public ArrivalQuotaPolicy {
        if (dailyLimit < 1 || dailyLimit > 40_000 || keyLimits == null || keyLimits.isEmpty()) {
            throw new IllegalArgumentException("도착정보 전체 예산은 1~40000회이며 사용할 키가 필요하다");
        }
        keyLimits = Map.copyOf(keyLimits);
        keyLimits.forEach((key, limit) -> {
            if (!key.matches("[a-d]") || limit == null || limit < 1 || limit > 10_000) {
                throw new IllegalArgumentException("도착정보 키별 승인 한도는 1~10000회여야 한다");
            }
        });
    }
}
