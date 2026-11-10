package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Instant;

/** 마지막 출발 관측은 다음 실제 정류장 통과 관측이 있어야 확정된다. */
public record AlightingAwarePassEvent(
    String vehicleId,
    long tripId,
    int stopOrder,
    AlightingAwareObservation arrival,
    AlightingAwareObservation departure,
    Instant confirmedAt,
    AlightingAwareObservation firstDeparture,
    AlightingAwareObservation confirmation
) {
    public AlightingAwarePassEvent(
        String vehicleId,
        final long tripId,
        final int stopOrder,
        AlightingAwareObservation arrival,
        AlightingAwareObservation departure,
        Instant confirmedAt
    ) {
        this(vehicleId, tripId, stopOrder, arrival, departure, confirmedAt, departure, departure);
    }
}
