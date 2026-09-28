package com.gustler.backend.forecasting.domain.statistics;

import java.util.Objects;

public record RebuildScope(String vehicleId) {

    private static final String WHOLE_ROUTE = "";

    public RebuildScope {
        Objects.requireNonNull(vehicleId, "정정 범위의 차량 ID가 필요하다");
    }

    public static RebuildScope wholeRoute() {
        return new RebuildScope(WHOLE_ROUTE);
    }

    public static RebuildScope vehicle(final String vehicleId) {
        if (vehicleId == null || vehicleId.isEmpty()) {
            throw new IllegalArgumentException("차량 정정에는 차량 ID가 필요하다");
        }
        return new RebuildScope(vehicleId);
    }

    public boolean isWholeRoute() {
        return vehicleId.isEmpty();
    }
}
