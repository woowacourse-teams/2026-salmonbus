package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.board.domain.VehicleForecast;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForecastResponse(
    Status status,
    Double seatAvailableProbability,
    Double expectedSeats
) {

    public enum Status { AVAILABLE, UNAVAILABLE }

    static ForecastResponse from(VehicleForecast forecast) {
        return switch (forecast) {
            case VehicleForecast.Available available -> new ForecastResponse(
                Status.AVAILABLE, available.seatAvailableProbability(), available.expectedSeats());
            case VehicleForecast.Unavailable ignored -> new ForecastResponse(Status.UNAVAILABLE, null, null);
        };
    }
}
