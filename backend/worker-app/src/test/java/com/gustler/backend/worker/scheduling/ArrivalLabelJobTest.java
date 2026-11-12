package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

class ArrivalLabelJobTest {
    private static final Instant COMPLETED = Instant.parse("2026-11-13T00:00:00Z");

    @Test
    void 진행이_없는_회차_뒤에는_유휴_간격만큼_쉬고_진행하면_다시_짧게_쉰다() {
        // given
        var progressed = new AtomicBoolean(true);
        var job = new ArrivalLabelJob(new EvaluateForecasts() {
            @Override
            public void settleArrivalLabels() {
            }

            @Override
            public boolean advance() {
                return progressed.get();
            }
        }, Duration.ofMinutes(1), Duration.ofSeconds(2), 0.25);
        var registrar = new ScheduledTaskRegistrar();
        job.configureTasks(registrar);
        Trigger trigger = registrar.getTriggerTaskList().getFirst().getTrigger();
        var context = new SimpleTriggerContext(COMPLETED, COMPLETED, COMPLETED);

        // when
        job.settleArrivalLabels();
        Instant afterProgress = trigger.nextExecution(context);
        progressed.set(false);
        job.settleArrivalLabels();
        Instant afterIdle = trigger.nextExecution(context);
        progressed.set(true);
        job.settleArrivalLabels();
        Instant afterResume = trigger.nextExecution(context);

        // then
        assertThat(afterProgress).isEqualTo(COMPLETED.plusSeconds(2));
        assertThat(afterIdle).isEqualTo(COMPLETED.plusSeconds(60));
        assertThat(afterResume).isEqualTo(COMPLETED.plusSeconds(2));
    }
}
