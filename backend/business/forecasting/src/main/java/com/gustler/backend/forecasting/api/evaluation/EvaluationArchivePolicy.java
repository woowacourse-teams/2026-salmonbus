package com.gustler.backend.forecasting.api.evaluation;

public record EvaluationArchivePolicy(boolean deleteEnabled, int pageSize) {
    public EvaluationArchivePolicy {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("이관 페이지는 1~100개여야 한다");
        }
    }
}
