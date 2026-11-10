package com.gustler.backend.api.salmon.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 이동 후보의 도보 합 상한. 15분을 넘으면 그 이동은 허용되지 않는다. */
@Component
public class SalmonWalkCap {
    private final Duration limit;

    public SalmonWalkCap(@Value("${salmon.action.max-walk:15m}") Duration limit) {
        if (limit == null || limit.isZero() || limit.isNegative()) {
            throw new IllegalArgumentException("max-walk must be positive");
        }
        this.limit = limit;
    }

    public Duration limit() {
        return limit;
    }

    public boolean exceeded(long walkMillis) {
        return walkMillis > limit.toMillis();
    }
}
