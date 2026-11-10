package com.gustler.backend.api.salmon.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 도보 시간에 곱한 뒤 마감과 비교한다. 1 미만은 거절한다. */
@Component
public class SalmonAlpha {
    private final BigDecimal alpha;

    public SalmonAlpha(@Value("${salmon.action.alpha:1.2}") BigDecimal alpha) {
        if (alpha == null || alpha.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("alpha must be at least 1");
        }
        this.alpha = alpha;
    }

    public BigDecimal alpha() {
        return alpha;
    }

    public long scaledMillis(long walkMillis) {
        return alpha.multiply(BigDecimal.valueOf(walkMillis)).setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
