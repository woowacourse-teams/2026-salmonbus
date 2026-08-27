package com.gustler.backend.api.vehicle.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class VehicleCachePolicy {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration PEAK_MAX_AGE = Duration.ofSeconds(15);
    private static final Duration REGULAR_MAX_AGE = Duration.ofSeconds(20);
    private static final Duration OVERNIGHT_MAX_AGE = Duration.ofSeconds(600);

    public Duration maxAgeAt(final OffsetDateTime pollAt) {
        final int hour = pollAt.atZoneSameInstant(SEOUL).getHour();

        if (hour >= 1 && hour < 4) {
            return OVERNIGHT_MAX_AGE;
        }
        if ((hour >= 7 && hour < 9) || (hour >= 17 && hour < 23)) {
            return PEAK_MAX_AGE;
        }
        return REGULAR_MAX_AGE;
    }
}
