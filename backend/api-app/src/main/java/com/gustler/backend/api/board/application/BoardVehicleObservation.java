package com.gustler.backend.api.board.application;

public record BoardVehicleObservation(
    String vehicleId,
    int sourceRowNumber,
    int passedStopOrder
) {
}
