package com.gustler.backend.api.salmon.application;

import java.math.BigDecimal;
import java.time.Duration;

/** application.yml의 운영 값과 같은 설정. */
final class SalmonSettingsFixture {
    private SalmonSettingsFixture() {
    }

    static SalmonActionSettings adopted() {
        return with(Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofMinutes(20));
    }

    static SalmonActionSettings with(Duration maxWalk, Duration maxInVehicle, Duration horizon) {
        return new SalmonActionSettings(true, maxWalk, 0.5, horizon, maxInVehicle, 0, new BigDecimal("1.2"), 1,
            Duration.ofSeconds(15));
    }
}
