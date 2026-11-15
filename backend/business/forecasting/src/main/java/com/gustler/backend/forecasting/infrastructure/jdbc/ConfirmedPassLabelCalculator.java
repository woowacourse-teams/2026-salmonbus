package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.PassLabelCalculator;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * 새 채점 단계의 정답 계산을 옛 확정 판 정산의 계산({@link ConfirmedAlightingAwareEvaluation#afterSeats})에 그대로 맡긴다.
 *
 * <p>통과 사건을 옛 계산이 받는 통과 모양으로 옮긴다. 옛 계산이 읽는 값은 정류장, 도착 전 좌석, 마지막 관측의 좌석과 확정 시각,
 * 직전 통과의 마지막 관측 좌석과 확정 시각이다. 옮긴 관측은 확정 판(노선 판본 번호가 양수)으로 표시한다. 스프링 빈이 아니다.
 */
public final class ConfirmedPassLabelCalculator implements PassLabelCalculator {
    @Override
    public PassLabelCalculation calculate(PassClosed pass, PassClosed previous, JsonNode evidence) {
        PassLabelOutcome outcome = ConfirmedAlightingAwareEvaluation.afterSeats(
            event(pass), previousEvent(previous), evidence);
        if ("SETTLED".equals(outcome.state())) {
            return new PassLabelCalculation(true, outcome.label(), null, outcome.parameterHash(), outcome.capacity());
        }
        return PassLabelCalculation.unsupported(outcome.reason());
    }

    private static AlightingAwarePassEvent previousEvent(PassClosed previous) {
        if (previous == null) {
            return null;
        }
        return event(previous);
    }

    private static AlightingAwarePassEvent event(PassClosed pass) {
        AlightingAwareObservation arrival = observation(pass, pass.arrivalObservationId(), pass.arrivalConfirmedAt(),
            pass.arrivalReceivedAt(), pass.arrivalSeats());
        AlightingAwareObservation departure = observation(pass, pass.lastObservationId(), pass.lastConfirmedAt(),
            pass.lastReceivedAt(), pass.lastSeats());
        AlightingAwareObservation first = observation(pass, pass.firstObservationId(), pass.firstConfirmedAt(),
            pass.firstConfirmedAt(), null);
        AlightingAwareObservation confirmation = observation(pass, pass.closureObservationId(), pass.closedAt(),
            pass.closedAt(), null);
        return new AlightingAwarePassEvent(pass.vehicleId(), pass.tripStartObservationId(), pass.stopOrder(), arrival,
            departure, pass.closedAt(), first, confirmation);
    }

    private static AlightingAwareObservation observation(
        PassClosed pass,
        final long id,
        Instant confirmedAt,
        Instant receivedAt,
        Integer seats
    ) {
        return new AlightingAwareObservation(id, pass.closureBatchId(), 0, confirmedAt, pass.vehicleId(),
            pass.stopOrder(), pass.stopOrder(), 0, seats, 0, Math.max(1, pass.routeVersionId()), receivedAt, false);
    }
}
