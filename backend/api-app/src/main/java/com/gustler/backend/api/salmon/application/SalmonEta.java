package com.gustler.backend.api.salmon.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 못 탈 확률이 이 폭 안이면 같은 묶음으로 보고 도보가 짧은 쪽을 고른다. 0이면 더 낮은 쪽만 남는다. */
@Component
public class SalmonEta {
    private final double eta;

    public SalmonEta(@Value("${salmon.action.eta:0}") double eta) {
        if (!Double.isFinite(eta) || eta < 0) {
            throw new IllegalArgumentException("eta must be zero or positive");
        }
        this.eta = eta;
    }

    public double eta() {
        return eta;
    }
}
