package com.gustler.backend.worker.scheduling;

import java.time.Duration;

/**
 * 정산 회차 사이에 쉴 시간을 정한다.
 *
 * <p>진행한 회차 뒤에는 실행 시간에 쉬는 비율을 곱한 만큼 쉬되 최소 간격보다 짧거나 유휴 간격보다 길게 쉬지 않는다.
 * 진행이 이어지는 동안 정산 스레드의 점유율은 {@code 1 / (1 + 쉬는 비율)}이고 회차 예산과는 관계없다.
 * 진행이 없으면 유휴 간격을, 실패하면 2초부터 두 배씩 늘려 최대 60초를 쉰다.
 */
final class SettlementDelay {
    private final Duration idle;
    private final Duration minimum;
    private final double restRatio;
    private Duration delay = Duration.ZERO;
    private int failures;

    SettlementDelay(Duration idle, Duration minimum, final double restRatio) {
        if (idle.isZero() || idle.isNegative()) {
            throw new IllegalArgumentException("Settlement interval must be positive");
        }
        if (minimum.isNegative() || minimum.compareTo(idle) > 0) {
            throw new IllegalArgumentException("Settlement minimum interval must be between zero and the idle interval");
        }
        if (!Double.isFinite(restRatio) || restRatio < 0 || restRatio > 100) {
            throw new IllegalArgumentException("Settlement rest ratio must be between 0 and 100");
        }
        this.idle = idle;
        this.minimum = minimum;
        this.restRatio = restRatio;
    }

    void succeeded(final boolean progressed, Duration elapsed) {
        failures = 0;
        if (!progressed) {
            delay = idle;
            return;
        }
        Duration rest = Duration.ofNanos((long) (elapsed.toNanos() * restRatio));
        if (rest.compareTo(minimum) < 0) {
            rest = minimum;
        }
        delay = rest.compareTo(idle) > 0 ? idle : rest;
    }

    void failed() {
        failures = Math.min(failures + 1, 6);
        delay = Duration.ofSeconds(Math.min(60, 1L << failures));
    }

    Duration current() {
        return delay;
    }
}
