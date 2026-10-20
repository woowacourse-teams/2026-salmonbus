package com.gustler.backend.observations.api;

import com.gustler.backend.observations.api.VehicleObservationsStored.Row;
import java.time.Instant;
import java.util.List;

/**
 * 저장된 차량 관측의 원본 행 식별과 배치 기준을 품질·후속 처리에 전달한다.
 */
public record VehicleObservationsStored(long batchId, long routeVersionId, Instant observedAt,
    List<Row> rows) {
    public VehicleObservationsStored {
        rows = List.copyOf(rows);
    }
    public record Row(long observationId, String vehicleId, Integer remainingSeats) {
    }
}
