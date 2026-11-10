package com.gustler.backend.api.salmon.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 계획 차내 시간 합의 상한. 기다리는 시간은 넣지 않는다. */
@Component
public class SalmonInVehicleCap {
    private final Duration limit;

    public SalmonInVehicleCap(@Value("${salmon.action.max-in-vehicle:20m}") Duration limit) {
        if (limit == null || limit.isZero() || limit.isNegative()) {
            throw new IllegalArgumentException("max-in-vehicle must be positive");
        }
        this.limit = limit;
    }

    public Duration limit() {
        return limit;
    }

    public boolean exceeded(long inVehicleMillis) {
        return inVehicleMillis > limit.toMillis();
    }
}
