package com.gustler.backend.collector;

import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * 다음 판을 언제 부를지 정한다.
 *
 * <p>간격이 시각대마다 달라 고정 지연으로는 안 된다. 직전 판이 끝난 시각이 어느 단계인지 보고
 * 그 단계의 간격만큼 뒤를 다음 차례로 잡는다.
 *
 * <p>끝난 시각을 기준으로 재는 것이라 상류가 느리게 답한 만큼 다음 판이 밀린다.
 * 시작 시각 기준으로 재면 응답이 간격보다 오래 걸릴 때 판이 겹쳐 쌓인다.
 */
public class AdaptiveCollectionTrigger implements Trigger {

    private final Clock clock;

    public AdaptiveCollectionTrigger(
        Clock clock
    ) {
        this.clock = clock;
    }

    @Override
    public Instant nextExecution(
        TriggerContext context
    ) {
        Instant lastCompletion = context.lastCompletion();
        if (lastCompletion == null) {
            return clock.instant();
        }
        return lastCompletion.plusSeconds(CollectionPhase.at(lastCompletion, clock).intervalSeconds());
    }
}
