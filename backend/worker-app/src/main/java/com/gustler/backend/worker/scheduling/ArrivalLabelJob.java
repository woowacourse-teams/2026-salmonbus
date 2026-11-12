package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ArrivalLabelJob implements SchedulingConfigurer {
    private final EvaluateForecasts service;
    private final SettlementDelay delay;

    public ArrivalLabelJob(EvaluateForecasts service, @Value("${forecast.settlement-interval}") Duration idle) {
        this.service = service;
        this.delay = new SettlementDelay(idle);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::settleArrivalLabels, context -> {
            Instant completed = context.lastCompletion();
            return completed == null ? context.getClock().instant() : completed.plus(delay.current());
        });
    }

    public void settleArrivalLabels() {
        long started = System.nanoTime();
        try {
            boolean progressed = service.advance();
            delay.succeeded(progressed, Duration.ofNanos(System.nanoTime() - started));
        } catch (RuntimeException failure) {
            delay.failed();
            throw failure;
        }
    }
}
