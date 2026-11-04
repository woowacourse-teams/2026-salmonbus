package com.gustler.backend.forecasting.domain.model.les;

import java.time.Instant;

/** 마지막 출발 관측은 다음 실제 정류장 통과 관측이 있어야 확정된다. */
public record LesPassEvent(
    String vehicleId,
    long tripId,
    int stopOrder,
    LesObservation arrival,
    LesObservation departure,
    Instant confirmedAt,
    LesObservation firstDeparture,
    LesObservation confirmation
) {
    public LesPassEvent(
        String vehicleId,
        final long tripId,
        final int stopOrder,
        LesObservation arrival,
        LesObservation departure,
        Instant confirmedAt) {
        this(vehicleId, tripId, stopOrder, arrival, departure, confirmedAt, departure, departure);
    }
}
