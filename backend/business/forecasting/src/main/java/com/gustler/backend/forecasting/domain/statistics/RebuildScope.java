package com.gustler.backend.forecasting.domain.statistics;

import java.util.Objects;

/**
 * 재집계의 노선 전체 또는 한 차량 범위를 나타낸다.
 * 전체 범위의 기존 빈 문자열 표기를 유지한다.
 */
public record RebuildScope(String vehicleId) {
    private static final String WHOLE_ROUTE = "";

    public RebuildScope {
        Objects.requireNonNull(vehicleId, "정정 범위의 차량 ID가 필요하다");
    }

    public static RebuildScope wholeRoute() {
        return new RebuildScope(WHOLE_ROUTE);
    }

    public static RebuildScope vehicle(String vehicleId) {
        if (vehicleId == null || vehicleId.isEmpty()) {
            throw new IllegalArgumentException("차량 정정에는 차량 ID가 필요하다");
        }
        return new RebuildScope(vehicleId);
    }

    public boolean isWholeRoute() {
        return vehicleId.isEmpty();
    }
}
