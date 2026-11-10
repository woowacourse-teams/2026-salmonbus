package com.gustler.backend.api.salmon.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 옮겨 타라고 하려면 필요한 이득의 하한. 0 초과 1 이하다. */
@Component
public class SalmonGainThreshold {
    private final double threshold;

    public SalmonGainThreshold(@Value("${salmon.action.gain-threshold:0.5}") double threshold) {
        if (!(threshold > 0 && threshold <= 1)) {
            throw new IllegalArgumentException("gain-threshold must be within (0, 1]");
        }
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }
}
