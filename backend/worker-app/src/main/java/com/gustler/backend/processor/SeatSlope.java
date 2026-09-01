package com.gustler.backend.processor;

import java.util.Objects;

/**
 * 같은 여정 안에서 직전 관측 대비 좌석이 몇 석 변했나. 줄면 음수다.
 *
 * <p>여정 밖으로 넘어가면 뜻이 없어서, 이을 수 없을 때는 사유를 들고 모른다고 답한다.
 */
public sealed interface SeatSlope {

    record Known(
        int seatChange
    ) implements SeatSlope {
    }

    record Unknown(
        TrajectoryGap gap
    ) implements SeatSlope {

        public Unknown {
            Objects.requireNonNull(gap, "좌석 변화를 모르면 끊긴 사유가 있어야 한다");
        }
    }
}
