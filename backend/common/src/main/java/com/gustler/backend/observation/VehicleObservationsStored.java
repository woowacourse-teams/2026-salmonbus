package com.gustler.backend.observation;

import java.time.Instant;
import java.util.List;

public record VehicleObservationsStored(long batchId, long routeVersionId, Instant observedAt,
                                        List<Row> rows) {
    public VehicleObservationsStored { rows = List.copyOf(rows); }
    public record Row(long observationId, String vehicleId, Integer remainingSeats) { }
}
