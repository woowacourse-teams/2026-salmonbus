package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/**
 * 같은 여정 안에서 직전 관측 대비 좌석 변화량. 감소하면 음수다.
 *
 * <p>여정이 바뀌면 좌석 변화를 비교하는 의미가 없으므로, 관측을 이을 수 없을 때는
 * 그 사유와 함께 알 수 없는 값으로 표현한다.
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
