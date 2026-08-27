package com.gustler.backend.collector;

import java.util.Objects;

public sealed interface RemainingSeats {

    static RemainingSeats from(
        final Integer remainingSeatCount
    ) {
        if (remainingSeatCount == null) {
            return new Unknown(SeatUnknownReason.NOT_REPORTED);
        }
        if (remainingSeatCount < 0) {
            return new Unknown(SeatUnknownReason.REPORTED_UNKNOWN);
        }
        return new Known(remainingSeatCount);
    }

    record Known(
        int seats
    ) implements RemainingSeats {

        public Known {
            if (seats < 0) {
                throw new IllegalArgumentException("아는 잔여석은 음수일 수 없다: " + seats);
            }
        }
    }

    record Unknown(
        SeatUnknownReason reason
    ) implements RemainingSeats {

        public Unknown {
            Objects.requireNonNull(reason, "잔여석을 모르면 사유가 있어야 한다");
        }
    }
}
