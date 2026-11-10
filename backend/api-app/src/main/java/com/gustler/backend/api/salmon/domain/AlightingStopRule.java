package com.gustler.backend.api.salmon.domain;

import java.util.Objects;

/**
 * 재탑승 구간시간 τ에 넣을 승차 순번과 하차 순번이 회차를 넘지 않는 반대 방향인지 판정한다.
 *
 * <p>정류장을 고르지 않는다. 호출자가 쌍을 넘긴다. 회차 순번 정류장까지를 UP으로 본다.
 * 이 자름은 route-catalog {@code RouteStops.directionOf}와 같다. 회차 순번이 없으면
 * 카탈로그는 모든 정류장을 UP으로 두지만, 그 표시는 회차를 넘지 않았다는 증거가 아니다.
 *
 * <p>판정 순서는 시각 값과 무관하다. 회차 미상, 순번이 늘지 않음, 회차 횡단, 원하는 방향 순이다.
 */
public final class AlightingStopRule {
    private AlightingStopRule() {
    }

    public enum Direction { UP, DOWN }

    public enum Decision {
        ACCEPTED,
        SEQUENCE_NOT_INCREASING,
        CROSSES_TURN,
        TURN_UNKNOWN,
        NOT_OPPOSITE_DIRECTION
    }

    public static Decision decide(Integer turnSequence, Direction desiredDirection,
                                  int boardSequence, int alightSequence) {
        Objects.requireNonNull(desiredDirection);
        requireSequence(boardSequence);
        requireSequence(alightSequence);
        if (turnSequence == null) {
            return Decision.TURN_UNKNOWN;
        }
        if (turnSequence < 1) {
            throw new IllegalArgumentException("Turn sequence must be positive");
        }
        if (alightSequence <= boardSequence) {
            return Decision.SEQUENCE_NOT_INCREASING;
        }
        Direction ride = side(boardSequence, turnSequence);
        if (ride != side(alightSequence, turnSequence)) {
            return Decision.CROSSES_TURN;
        }
        if (ride == desiredDirection) {
            return Decision.NOT_OPPOSITE_DIRECTION;
        }
        return Decision.ACCEPTED;
    }

    private static Direction side(int sequence, int turnSequence) {
        return sequence <= turnSequence ? Direction.UP : Direction.DOWN;
    }

    private static void requireSequence(int sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Stop sequence must be positive");
        }
    }
}
