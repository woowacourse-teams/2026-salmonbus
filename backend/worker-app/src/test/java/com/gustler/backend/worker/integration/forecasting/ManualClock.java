package com.gustler.backend.worker.integration.forecasting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 시험이 옮길 때만 가는 시계다. worker의 {@link Clock} 빈 자리에 넣으면 예보의 처리 시작 시각, 미처리 배치 조회의 유효 시간 범위, 정산의 판정 시각이 모두
 * 시험이 정한 시각에서 나온다. 실제로 흐른 시간은 들어가지 않으므로 시험을 돌리는 시각과 기계의 속도가 판정을 바꾸지 못한다.
 *
 * <p>시작 시각은 한국 정오로 고정한다. 시험이 옮기는 시간은 길어야 몇 분이라 도중에 한국 날짜가 바뀌지 않는다. DB의 {@code now()}와는
 * 날짜부터 다르다. 관측 테이블의 파티션 기준 날짜 기본값만 DB의 시각에서 오고, 예보와 정산은 그 열을 읽지 않는다.
 *
 * <p>시각은 뒤로 가지 않는다. 정산의 복구는 복구하는 시각보다 앞에 수신하거나 닫힌 기록만 읽으므로, worker를 다시 띄우는 시험은 띄우기
 * 전에 시계를 저장된 기록보다 뒤로 옮겨야 한다. 여러 스레드가 같이 읽어도 된다.
 */
final class ManualClock extends Clock {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOON = Instant.parse("2026-03-04T03:00:00Z");

    private final AtomicReference<Instant> current = new AtomicReference<>(NOON);

    /**
     * 시계를 앞으로 옮긴다.
     *
     * @param step 옮길 시간. 0보다 길다
     * @return 옮긴 뒤의 시각
     */
    Instant advance(Duration step) {
        if (step.isNegative() || step.isZero()) {
            throw new IllegalArgumentException("시계는 앞으로만 옮긴다: " + step);
        }
        return current.updateAndGet(at -> at.plus(step));
    }

    /**
     * 시계를 {@code target}으로 옮긴다. 이미 그 시각을 지났으면 그대로 둔다.
     *
     * @param target 옮길 시각
     */
    void moveTo(Instant target) {
        current.accumulateAndGet(target, ManualClock::later);
    }

    /** 지금 시각을 한국 시간대로 반환한다. */
    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(current.get(), SEOUL);
    }

    private static Instant later(Instant left, Instant right) {
        if (left.isAfter(right)) {
            return left;
        }
        return right;
    }

    @Override
    public ZoneId getZone() {
        return SEOUL;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }

    /** worker 컨텍스트의 시계를 컨텍스트마다 새로 만든 {@link ManualClock}으로 바꾼다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Replacement {
        @Bean
        @Primary
        ManualClock manualClock() {
            return new ManualClock();
        }
    }
}
