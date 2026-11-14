package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * 미완료 예보의 도착 정산 회차를 한 스레드에서 이어 실행한다.
 *
 * <p>다음 회차는 앞 회차가 끝난 뒤 {@link SettlementDelay}가 정한 시간만큼 쉬고 시작한다. 진행이 없으면
 * {@code forecast.settlement-interval}을 쉬고, 진행했으면 실행 시간에 {@code forecast.settlement-rest-ratio}를 곱한 시간을
 * {@code forecast.settlement-minimum-interval} 이상 쉰다.
 *
 * <p>새 채점이 본선({@code forecast.scoring=v2})이면 등록하지 않는다. 이 작업이 부르는 일은 하차 반영 모델의 옛 정산
 * (대기열 채우기, 투영 정리, 정산) 하나뿐이라 작업 전체를 끈다.
 */
@Component
@ConditionalOnExpression(ArrivalLabelJob.LEGACY_SETTLEMENT)
public class ArrivalLabelJob implements SchedulingConfigurer {
    /** 옛 정산을 돌리는 조건. */
    static final String LEGACY_SETTLEMENT = "${forecast.enabled:false}"
        + " and !'${forecast.scoring:off}'.equalsIgnoreCase('v2')";
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
