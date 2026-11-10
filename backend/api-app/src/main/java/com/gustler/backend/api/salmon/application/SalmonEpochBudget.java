package com.gustler.backend.api.salmon.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 정류장 목록이 바뀔 때 기준선을 다시 읽는 횟수. 1 미만은 거절한다. */
@Component
public class SalmonEpochBudget {
    private final int budget;

    public SalmonEpochBudget(@Value("${salmon.action.epoch-budget:2}") int budget) {
        if (budget < 1) {
            throw new IllegalArgumentException("epoch-budget must be at least 1");
        }
        this.budget = budget;
    }

    public int budget() {
        return budget;
    }

    public boolean exhausted(int epochNumber) {
        return epochNumber > budget;
    }
}
