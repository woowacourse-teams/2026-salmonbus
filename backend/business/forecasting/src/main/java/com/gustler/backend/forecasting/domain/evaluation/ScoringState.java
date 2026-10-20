package com.gustler.backend.forecasting.domain.evaluation;

/**
 * 예보의 도착 정산 상태다.
 * 관측을 찾지 못한 상태와 정산한 결과를 구분한다.
 */
public enum ScoringState {
    PENDING,
    SETTLED,
    SKIPPED,
    LOST,
    SEAT_MISSING,
    QUALITY_EXCLUDED,
    ;

    public boolean scorable() {
        return this == SETTLED;
    }
}
