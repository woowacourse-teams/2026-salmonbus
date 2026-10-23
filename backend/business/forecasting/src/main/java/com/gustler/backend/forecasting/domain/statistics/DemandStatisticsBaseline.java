package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;

/**
 * 통계 누적에 필요한 초기화 여부와 고정한 원본 범위를 전달한다.
 */
public record DemandStatisticsBaseline(boolean initialized, Instant dataUntil) {
    public Instant fixUntil(Instant candidate) {
        if (dataUntil != null && dataUntil.isAfter(candidate)) {
            return dataUntil;
        }
        return candidate;
    }
}
