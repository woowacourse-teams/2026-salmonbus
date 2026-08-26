package com.gustler.backend.processor;

public interface SeatForecastModel {

    SeatForecastResult predict(
        final VehicleStopTarget target
    );
}
