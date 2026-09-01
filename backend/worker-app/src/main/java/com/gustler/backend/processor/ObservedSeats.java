package com.gustler.backend.processor;

import java.util.Objects;

/**
 * 관측 하나가 말하는 잔여석. 아는 값이거나, 모르는 사유다.
 *
 * <p>적재는 아는 값과 모르는 사유를 열 둘로 갈라 두는데, 읽을 때 잔여석 하나로 합치면
 * "상류가 모른다고 답했다"와 "상류가 값을 아예 안 줬다"가 다시 뭉개진다. 그래서 갈린 채로 받는다.
 */
public sealed interface ObservedSeats {

    /**
     * 열 둘을 그대로 받아 하나로 세운다.
     *
     * <p>V4 의 ck_observation_seats_exclusive_with_reason 이 둘 중 하나만 차 있는 것을 보장한다.
     * 그 규칙이 깨진 행이 오면 여기서 멈추는 편이 조용히 뭉개는 것보다 낫다.
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
