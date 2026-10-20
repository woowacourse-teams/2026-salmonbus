package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.observation.ObservationFreshness;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 차량 관측의 기준 시각과 만료 시각을 비교해 재사용 가능 여부를 판단한다.
 */
@Component
@RequiredArgsConstructor
public class VehicleFreshnessPolicy {
    private final Clock clock;

    public OffsetDateTime staleAt(OffsetDateTime observedAt) {
        return ObservationFreshness.staleAt(observedAt);
    }

    public boolean isStale(OffsetDateTime staleAt) {
        return clock.instant().isAfter(staleAt.toInstant());
    }
}
