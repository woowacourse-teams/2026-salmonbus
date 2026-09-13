package com.gustler.backend.processor;

import java.util.Objects;

/**
 * 만석이 몇 정류장째 이어지고 있나. 같은 정류소를 여러 번 관측해도 정류장 하나로 센다.
 *
 * <p>여유석이 있던 지점까지 다 봤으면 정확한 수이고, 이력이 끊긴 자리에서 멈췄으면 최소값이다.
 * 실제로는 그보다 길 수 있다.
 */
public sealed interface FullSeatStreak {

    int stopCount();

    record SeenToEnd(
        int stopCount
    ) implements FullSeatStreak {

        public SeenToEnd {
            if (stopCount < 0) {
                throw new IllegalArgumentException("연속 만석 정류장 수는 0 이상이다: " + stopCount);
            }
        }
    }

    record CutByGap(
        int stopCount,
        TrajectoryGap gap
    ) implements FullSeatStreak {

        public CutByGap {
            if (stopCount < 0) {
                throw new IllegalArgumentException("연속 만석 정류장 수는 0 이상이다: " + stopCount);
            }
            Objects.requireNonNull(gap, "연속 만석이 끊겼으면 끊긴 사유가 있어야 한다");
        }
    }
}
