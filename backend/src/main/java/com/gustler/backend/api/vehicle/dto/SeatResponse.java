package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.domain.VehicleSeat;

public sealed interface SeatResponse permits SeatResponse.Exact, SeatResponse.Unknown {

    static SeatResponse from(VehicleSeat seat) {
        if (seat instanceof VehicleSeat.Exact exact) {
            return new Exact(Kind.EXACT, exact.remaining());
        }
        return new Unknown(Kind.UNKNOWN);
    }

    enum Kind {

        EXACT,
        UNKNOWN
    }

    record Exact(Kind kind, int remaining) implements SeatResponse {
    }

    record Unknown(Kind kind) implements SeatResponse {
    }
}
