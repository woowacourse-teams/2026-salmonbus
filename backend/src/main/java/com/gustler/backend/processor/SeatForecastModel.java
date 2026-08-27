package com.gustler.backend.processor;

public interface SeatForecastModel {

    SeatForecastResult predict(
        VehicleStopTarget target
    );
}
