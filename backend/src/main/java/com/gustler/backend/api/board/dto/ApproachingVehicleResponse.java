package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.board.domain.ApproachingVehicle;

public sealed interface ApproachingVehicleResponse
    permits ApproachingVehicleResponse.Forecast, ApproachingVehicleResponse.Unknown {

    static ApproachingVehicleResponse from(
        ApproachingVehicle vehicle
    ) {
        if (vehicle instanceof ApproachingVehicle.Forecast forecast) {
            return new Forecast(
                forecast.vehicleId(),
                forecast.horizonStops(),
                forecast.seatAvailableProbability(),
                forecast.expectedSeats()
            );
        }
        return new Unknown(
            vehicle.vehicleId(),
            vehicle.horizonStops()
        );
    }

    enum Kind {

        FORECAST,
        UNKNOWN,
        ;
    }

    record Forecast(
        String vehicleId,
        int horizonStops,
        double seatAvailableProbability,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Double expectedSeats
    ) implements ApproachingVehicleResponse {

        @JsonProperty("kind")
        public Kind kind() {
            return Kind.FORECAST;
        }
    }

    record Unknown(
        String vehicleId,
        int horizonStops
    ) implements ApproachingVehicleResponse {

        @JsonProperty("kind")
        public Kind kind() {
            return Kind.UNKNOWN;
        }
    }
}
