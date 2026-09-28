package com.gustler.backend.api.vehicle.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import java.util.Objects;

public sealed interface SeatResponse permits SeatResponse.Exact, SeatResponse.Unknown {

    static SeatResponse from(VehicleSeat seat) {
        if (seat instanceof VehicleSeat.Exact exact) {
            return new Exact(exact.remaining());
        }
        return new Unknown((VehicleSeat.Unknown) seat);
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

    record Unknown(VehicleSeat.Unknown reason) implements SeatResponse {

        public Unknown {
            Objects.requireNonNull(reason, "UNKNOWN 응답에는 미제공 사유가 필요합니다.");
        }

        @JsonProperty("kind")
        public Kind kind() {
            return Kind.UNKNOWN;
        }
    }
}
