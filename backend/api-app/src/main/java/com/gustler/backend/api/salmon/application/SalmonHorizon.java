package com.gustler.backend.api.salmon.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 요청 시각에 더하는 마감. 이 시각보다 늦게 타면 그 요청에서는 실패다. */
@Component
public class SalmonHorizon {
    private final Duration horizon;

    public SalmonHorizon(@Value("${salmon.action.horizon:20m}") Duration horizon) {
        if (horizon == null || horizon.isZero() || horizon.isNegative()) {
            throw new IllegalArgumentException("horizon must be positive");
        }
        this.horizon = horizon;
    }

    public Duration horizon() {
        return horizon;
    }
}
