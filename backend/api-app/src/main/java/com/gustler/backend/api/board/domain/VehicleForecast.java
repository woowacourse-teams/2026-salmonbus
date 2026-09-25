package com.gustler.backend.api.board.domain;

public sealed interface VehicleForecast {

    record Available(double seatAvailableProbability, Double expectedSeats) implements VehicleForecast {
    }

    record Unavailable() implements VehicleForecast {
    }
}
