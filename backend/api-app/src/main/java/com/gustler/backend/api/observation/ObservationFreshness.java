package com.gustler.backend.api.observation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class ObservationFreshness {

    public static final Duration MAX_WINDOW = Duration.ofMinutes(10);
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private ObservationFreshness() {}

    public static OffsetDateTime staleAt(OffsetDateTime observedAt) {
        final int hour = observedAt.atZoneSameInstant(KOREA).getHour();
        return observedAt.plus(hour >= 1 && hour < 4 ? MAX_WINDOW : DEFAULT_WINDOW);
    }
}
