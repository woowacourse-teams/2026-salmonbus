package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/**
 * 관측한 잔여석과 값을 알 수 없을 때의 사유.
 *
 * <p>적재 시 잔여석과 사유를 별도 열에 저장한다. 조회 시 잔여석 하나로 합치지 않고
 * "상류가 모른다고 답했다"와 "상류가 값을 아예 안 줬다"를 구분해 받는다.
 */
public sealed interface ObservedSeats {
    /**
     * 잔여석과 사유의 두 열을 하나의 값으로 구성한다.
     *
     * <p>V4 의 ck_observation_seats_exclusive_with_reason 이 둘 중 하나에만 값이 있음을 보장한다.
     * 이 규칙이 깨진 행은 사유를 누락시키지 않고 오류로 처리한다.
     */
    static ObservedSeats of(
        Integer remainingSeats,
        SeatUnknownReason reason
    ) {
        if (remainingSeats != null && reason != null) {
            throw new IllegalArgumentException("잔여석과 모르는 사유가 같이 있을 수 없다: %d, %s"
                .formatted(remainingSeats, reason));
        }
        if (remainingSeats == null && reason == null) {
            throw new IllegalArgumentException("잔여석과 모르는 사유 중 하나는 있어야 한다");
        }
        if (remainingSeats == null) {
            return new Unknown(reason);
        }
        return new Known(remainingSeats);
    }

    record Known(
        int seats
    ) implements ObservedSeats {
        public Known {
            if (seats < 0) {
                throw new IllegalArgumentException("아는 잔여석은 음수일 수 없다: " + seats);
            }
        }
    }

    record Unknown(
        SeatUnknownReason reason
    ) implements ObservedSeats {
        public Unknown {
            Objects.requireNonNull(reason, "잔여석을 모르면 사유가 있어야 한다");
        }
    }
}
