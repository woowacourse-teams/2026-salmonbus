package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * 미완료 예보의 도착 정산 회차를 한 스레드에서 이어 실행한다.
 *
 * <p>다음 회차는 앞 회차가 끝난 뒤 {@link SettlementDelay}가 정한 시간만큼 쉬고 시작한다. 진행이 없으면
 * {@code forecast.settlement-interval}을 쉬고, 진행했으면 실행 시간에 {@code forecast.settlement-rest-ratio}를 곱한 시간을
 * {@code forecast.settlement-minimum-interval} 이상 쉰다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ArrivalLabelJob implements SchedulingConfigurer {
    private final EvaluateForecasts service;
    private final SettlementDelay delay;

    public ArrivalLabelJob(
        EvaluateForecasts service,
        @Value("${forecast.settlement-interval}") Duration idle,
        @Value("${forecast.settlement-minimum-interval:100ms}") Duration minimum,
        @Value("${forecast.settlement-rest-ratio:0.25}") final double restRatio
    ) {
        this.service = service;
        this.delay = new SettlementDelay(idle, minimum, restRatio);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::settleArrivalLabels, context -> {
            Instant completed = context.lastCompletion();
            return completed == null ? context.getClock().instant() : completed.plus(delay.current());
        });
    }

    public void settleArrivalLabels() {
        final long started = System.nanoTime();
        try {
            final boolean progressed = service.advance();
            delay.succeeded(progressed, Duration.ofNanos(System.nanoTime() - started));
        } catch (RuntimeException failure) {
            delay.failed();
            throw failure;
        }
    }
}
