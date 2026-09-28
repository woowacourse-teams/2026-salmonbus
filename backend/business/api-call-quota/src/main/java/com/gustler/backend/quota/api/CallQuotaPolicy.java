package com.gustler.backend.quota.api;

import com.gustler.backend.quota.domain.CallQuota;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 활용신청한 API마다 따로 받는 하루 호출 한도.
 *
 * <p>한도는 서비스키 전체가 아니라 API 단위로 적용되므로 위치정보와 노선정보의 한도가 다를 수 있다.
 */
public record CallQuotaPolicy(Map<CallQuota, Integer> dailyLimits, List<String> locationKeyAliases) {

    public static final String PRIMARY_KEY_ALIAS = "a";

    public CallQuotaPolicy {
        Objects.requireNonNull(dailyLimits, "API별 하루 호출 한도가 필요하다");
        Map<CallQuota, Integer> limits = new EnumMap<>(CallQuota.class);
        limits.putAll(dailyLimits);
        for (CallQuota service : CallQuota.values()) {
            Integer limit = limits.get(service);
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException("하루 호출 한도는 API마다 1 이상이어야 한다: " + service);
            }
        }
        dailyLimits = Collections.unmodifiableMap(limits);
        Objects.requireNonNull(locationKeyAliases, "위치정보 키 별칭이 필요하다");
        if (!locationKeyAliases.contains(PRIMARY_KEY_ALIAS)
            || locationKeyAliases.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
            throw new IllegalArgumentException("위치정보 키 별칭에는 기본 키가 있어야 한다: " + locationKeyAliases);
        }
        locationKeyAliases = List.copyOf(locationKeyAliases);
    }

    public CallQuotaPolicy(Map<CallQuota, Integer> dailyLimits) {
        this(dailyLimits, List.of(PRIMARY_KEY_ALIAS));
    }

    /** 활용신청한 API가 모두 같은 한도를 받은 경우. */
    public static CallQuotaPolicy sameForEveryApi(final int dailyLimit) {
        return sameForEveryApi(dailyLimit, List.of(PRIMARY_KEY_ALIAS));
    }

    public static CallQuotaPolicy sameForEveryApi(final int dailyLimit, List<String> locationKeyAliases) {
        Map<CallQuota, Integer> limits = new EnumMap<>(CallQuota.class);
        for (CallQuota service : CallQuota.values()) {
            limits.put(service, dailyLimit);
        }
        return new CallQuotaPolicy(limits, locationKeyAliases);
    }

    public int limitOf(CallQuota service) {
        return dailyLimits.get(Objects.requireNonNull(service, "호출 서비스가 필요하다"));
    }
}
