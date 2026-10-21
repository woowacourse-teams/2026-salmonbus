package com.gustler.backend.api.vehicle.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 차량 조회의 수집 간격에 맞춰 클라이언트 캐시 수명을 정한다.
 */
@Component
public class VehicleCachePolicy {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration PEAK_MAX_AGE = Duration.ofSeconds(15);
    private static final Duration REGULAR_MAX_AGE = Duration.ofSeconds(20);
    private static final Duration OVERNIGHT_MAX_AGE = Duration.ofSeconds(600);

    /**
     * max-age는 응답을 받은 시점부터 계산하므로 관측 시각이 아닌 응답 시각으로 구간을 정한다.
     */
    public Duration maxAgeAt(OffsetDateTime respondedAt) {
        final int hour = respondedAt.atZoneSameInstant(SEOUL).getHour();

        if (isOvernight(hour)) {
            return OVERNIGHT_MAX_AGE;
        }
        if (isPeakHour(hour)) {
            return PEAK_MAX_AGE;
        }
        return REGULAR_MAX_AGE;
    }

    private boolean isOvernight(int hour) {
        return hour >= 1 && hour < 4;
    }

    private boolean isPeakHour(int hour) {
        return isMorningPeak(hour) || isEveningPeak(hour);
    }

    private boolean isMorningPeak(int hour) {
        return hour >= 7 && hour < 9;
    }

    private boolean isEveningPeak(int hour) {
        return hour >= 17 && hour < 23;
    }
}
