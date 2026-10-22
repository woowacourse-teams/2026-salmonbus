package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class AdaptiveCollectionTriggerTest {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    /** 한국 시각 2026-08-28 08:00. 아침 첨두라 20초다. */
    private static final Instant MORNING_PEAK_MOMENT = Instant.parse("2026-08-27T23:00:00Z");
    /** 한국 시각 2026-08-28 02:00. 심야라 600초다. */
    private static final Instant DEEP_NIGHT_MOMENT = Instant.parse("2026-08-27T17:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, KOREA);
    private final AdaptiveCollectionTrigger trigger = new AdaptiveCollectionTrigger(clock);

    @Test
    void 처음_도는_판은_기다리지_않고_바로_부른다() {
        // when
        Instant actual = trigger.nextExecution(new SimpleTriggerContext(clock));

        // then
        assertThat(actual).isEqualTo(NOW);
    }

    @Test
    void 아침_첨두에_끝난_판의_다음_차례는_20초_뒤다() {
        // when
        Instant actual = trigger.nextExecution(contextCompletedAt(MORNING_PEAK_MOMENT));

        // then
        assertThat(actual).isEqualTo(MORNING_PEAK_MOMENT.plusSeconds(20));
    }

    @Test
    void 심야에_끝난_판의_다음_차례는_600초_뒤다() {
        // when
        Instant actual = trigger.nextExecution(contextCompletedAt(DEEP_NIGHT_MOMENT));

        // then
        assertThat(actual).isEqualTo(DEEP_NIGHT_MOMENT.plusSeconds(600));
    }

    @Test
    void 상류가_늦게_답하면_다음_차례도_그만큼_밀린다() {
        // given
        // 예정 시각은 08:00 이었지만 실제 완료는 5초 뒤다.
        Instant completedLate = MORNING_PEAK_MOMENT.plusSeconds(5);
        SimpleTriggerContext context = new SimpleTriggerContext(clock);
        context.update(MORNING_PEAK_MOMENT, MORNING_PEAK_MOMENT, completedLate);

        // when
        Instant actual = trigger.nextExecution(context);

        // then
        assertThat(actual).isEqualTo(completedLate.plusSeconds(20));
    }

    private SimpleTriggerContext contextCompletedAt(
        Instant completion
    ) {
        SimpleTriggerContext context = new SimpleTriggerContext(clock);
        context.update(completion, completion, completion);
        return context;
    }
}
