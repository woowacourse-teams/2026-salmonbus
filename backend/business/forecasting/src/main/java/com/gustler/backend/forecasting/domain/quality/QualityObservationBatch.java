package com.gustler.backend.forecasting.domain.quality;

import java.time.Instant;
import java.util.List;

public record QualityObservationBatch(long batchId, long routeVersionId, Instant observedAt, List<Row> rows) {
    public QualityObservationBatch { rows = List.copyOf(rows); }
    public List<Row> anomalies() {
        return rows.stream().filter(row -> OneWayTripAssessment.requiresInvestigation(row.vehicleId(), row.remainingSeats())).toList();
    }
    public record Row(long observationId, String vehicleId, Integer remainingSeats) { }
}
