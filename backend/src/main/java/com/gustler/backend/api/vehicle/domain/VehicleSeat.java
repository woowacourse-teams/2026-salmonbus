package com.gustler.backend.api.vehicle.domain;

public sealed interface VehicleSeat permits VehicleSeat.Exact, VehicleSeat.Unknown {

    static VehicleSeat from(Integer remainingSeats) {
        if (remainingSeats == null || remainingSeats < 0) {
            return Unknown.INSTANCE;
        }
        return new Exact(remainingSeats);
    }

    record Exact(int remaining) implements VehicleSeat {

        public Exact {
            if (remaining < 0) {
                throw new IllegalArgumentException("remaining은 0 이상이어야 합니다.");
            }
        }
    }

    enum Unknown implements VehicleSeat {

        INSTANCE
    }
}
