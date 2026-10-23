package com.gustler.backend.worker.scheduling;

import com.gustler.backend.observations.api.CollectionTiming;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * 다음 수집의 실행 시각을 정한다.
 *
 * <p>간격이 시간대마다 달라 고정 지연으로는 지정할 수 없다. 직전 수집이 끝난 시각의 시간대를 보고
 * 해당 간격만큼 뒤를 다음 실행 시각으로 정한다.
 *
 * <p>완료 시각을 기준으로 하므로 상류가 늦게 응답한 만큼 다음 수집도 늦어진다.
 * 시작 시각을 기준으로 하면 응답이 간격보다 오래 걸릴 때 수집이 겹쳐 쌓인다.
 */
public class AdaptiveCollectionTrigger implements Trigger {
    private final Clock clock;
    private final Duration fixedInterval;

    public AdaptiveCollectionTrigger(
        Clock clock
    ) {
        this(clock, null);
    }

    public AdaptiveCollectionTrigger(Clock clock, Duration fixedInterval) {
        this.clock = clock;
        this.fixedInterval = fixedInterval;
    }

    @Override
    public Instant nextExecution(
        TriggerContext context
    ) {
        Instant lastCompletion = context.lastCompletion();
        if (lastCompletion == null) {
            return clock.instant();
        }
        if (fixedInterval == null) {
            return lastCompletion.plusSeconds(CollectionTiming.intervalSeconds(lastCompletion));
        }
        return lastCompletion.plus(fixedInterval);
    }
}
