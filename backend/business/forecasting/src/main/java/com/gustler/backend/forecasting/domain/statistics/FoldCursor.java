package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.Objects;

public record FoldCursor(String vehicleId, Instant arrivedHourStart, int targetStopOrder) {

    public FoldCursor {
        Objects.requireNonNull(vehicleId, "차량 ID가 필요하다");
        Objects.requireNonNull(arrivedHourStart, "도착 시간대가 필요하다");
    }
}
