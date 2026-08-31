package com.gustler.backend.processor;

import java.time.Instant;
import java.util.Objects;

/**
 * 같은 정류소를 나보다 앞서 지난 차.
 *
 * <p>앞차가 만석이면 뒷차도 만석일 확률이 크게 오른다. 언제 지났는지를 같이 들어서,
 * 얼마나 오래된 앞차까지 쳐줄지는 읽는 쪽이 정한다.
 */
public sealed interface PrecedingVehicle {

    record Known(
        String vehicleId,
        int remainingSeats,
        Instant observedAt
    ) implements PrecedingVehicle {

        public Known {
            Objects.requireNonNull(vehicleId, "앞차를 알면 차량 아이디가 있어야 한다");
            Objects.requireNonNull(observedAt, "앞차를 알면 관측 시각이 있어야 한다");
            if (remainingSeats < 0) {
                throw new IllegalArgumentException("아는 잔여석은 음수일 수 없다: " + remainingSeats);
            }
        }
    }

    record Unknown(
        TrajectoryGap gap
    ) implements PrecedingVehicle {

        public Unknown {
            Objects.requireNonNull(gap, "앞차를 모르면 끊긴 사유가 있어야 한다");
        }
    }
}
