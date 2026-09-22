package com.gustler.backend.observation;

import java.time.Instant;
import java.util.List;

/** 저장된 관측의 사실만 전달한다. 모델 범위 판정은 수신자가 담당한다. */
public record VehicleObservationsStored(long batchId, long routeVersionId, Instant observedAt,
                                        List<Row> rows) {
    public VehicleObservationsStored { rows = List.copyOf(rows); }
    public record Row(long observationId, String vehicleId, Integer remainingSeats) { }
}
