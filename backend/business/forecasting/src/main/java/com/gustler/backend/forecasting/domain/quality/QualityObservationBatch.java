package com.gustler.backend.forecasting.domain.quality;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

public record QualityObservationBatch(long batchId, long routeVersionId, Instant observedAt, List<Row> rows) {
    public QualityObservationBatch { rows = List.copyOf(rows); }
    public List<Row> anomalies() {
        return rows.stream().filter(row -> OneWayTripAssessment.requiresInvestigation(row.vehicleId(), row.remainingSeats())).toList();
    }
    /** 한 배치에서 같은 차량의 이상이 반복되면 먼저 받은 관측을 근거로 삼는다. */
    public List<Row> firstAnomalies() {
        final var first = new LinkedHashMap<String, Row>();
        for (final Row row : anomalies()) { first.putIfAbsent(row.vehicleId(), row); }
        return List.copyOf(first.values());
    }
    public record Row(long observationId, String vehicleId, Integer remainingSeats) { }
}
