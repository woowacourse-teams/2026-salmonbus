package com.gustler.backend.quota.api;

import java.util.Map;

/** 도착정보 예산은 위치/노선 수집의 설정과 독립이다. 기존 정책에 필수 설정을 추가하지 않는다. */
public record ArrivalQuotaPolicy(int dailyLimit, Map<String, Integer> keyLimits) {
    public static final String TOTAL = "__total__";

    public ArrivalQuotaPolicy {
        if (dailyLimit < 1 || keyLimits == null || keyLimits.isEmpty()) {
            throw new IllegalArgumentException("도착정보 전체 예산은 1회 이상이며 사용할 키가 필요하다");
        }
        keyLimits = Map.copyOf(keyLimits);
        keyLimits.forEach((key, limit) -> {
            if (!key.matches("[a-z0-9]{1,16}") || limit == null || limit < 1) {
                throw new IllegalArgumentException("도착정보 키 별칭은 영문 소문자와 숫자 1~16자, 키별 한도는 1회 이상이어야 한다");
            }
        });
    }
}
