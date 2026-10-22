package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 도착정보 수신 결과의 갱신 필요 여부·사용 가능 여부·캐시 수명을 같은 시각 기준으로 판단한다.
 */
@Component
public class ArrivalFreshnessPolicy {
    private static final long MAX_CACHE_SECONDS = 5;

    public boolean refreshDue(ArrivalSnapshot snapshot, Instant now) {
        return snapshot == null || !now.isBefore(snapshot.refreshAfterAt());
    }

    public boolean usable(ArrivalSnapshot snapshot, Instant now) {
        return snapshot != null && now.isBefore(snapshot.staleAt());
    }

    /**
     * 수신 결과의 유효기한과 다음 갱신 시각을 넘지 않는 초 단위 캐시 수명을 반환한다. 사용할 수 없으면 0이다.
     */
    public Duration cacheMaxAge(ArrivalSnapshot snapshot, Instant now) {
        if (!usable(snapshot, now)) {
            return Duration.ZERO;
        }
        final long remaining = Duration.between(now, snapshot.staleAt()).toSeconds();
        final long untilRefresh = Duration.between(now, snapshot.refreshAfterAt()).toSeconds();
        return Duration.ofSeconds(Math.max(0, Math.min(MAX_CACHE_SECONDS, Math.min(remaining, untilRefresh))));
    }
}
