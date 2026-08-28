package com.gustler.backend.api.vehicle.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;

public sealed interface SeatResponse permits SeatResponse.Exact, SeatResponse.Unknown {

    static SeatResponse from(VehicleSeat seat) {
        if (seat instanceof VehicleSeat.Exact exact) {
            return new Exact(exact.remaining());
        }
        return new Unknown();
    }

    enum Kind {

        EXACT,
        UNKNOWN,
        ;
    }

    record Exact(int remaining) implements SeatResponse {

        @JsonProperty("kind")
        public Kind kind() {
            return Kind.EXACT;
        }
    }

    record Unknown() implements SeatResponse {

        @JsonProperty("kind")
        public Kind kind() {
            return Kind.UNKNOWN;
        }
    }
}
