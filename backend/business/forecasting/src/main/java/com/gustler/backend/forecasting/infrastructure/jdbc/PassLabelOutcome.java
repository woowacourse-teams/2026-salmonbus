package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import java.util.function.Function;

/**
 * 통과 하나의 정답 계산 결과다. 정답이 정해지면 상태가 {@code SETTLED}이고 정답 값·계수 해시·정원과 계산에 쓴 통과를 담는다.
 * 정해지지 않으면 {@code UNSUPPORTED}와 사유만 담는다.
 */
record PassLabelOutcome(
    String state,
    Double label,
    String reason,
    AlightingAwarePassEvent event,
    AlightingAwarePassEvent previous,
    String parameterHash,
    Integer capacity
) {
    static PassLabelOutcome unsupported(String reason) {
        return new PassLabelOutcome("UNSUPPORTED", null, reason, null, null, null, null);
    }

    Long preObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::arrival);
    }

    Long postObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::departure);
    }

    Long closureObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::confirmation);
    }

    Long previousPostObservationId() {
        return eventObservationId(previous, AlightingAwarePassEvent::departure);
    }

    Long previousClosureObservationId() {
        return eventObservationId(previous, AlightingAwarePassEvent::confirmation);
    }

    private Long eventObservationId(
        AlightingAwarePassEvent pass,
        Function<AlightingAwarePassEvent, AlightingAwareObservation> observation
    ) {
        if (pass == null) {
            return null;
        }
        return observation.apply(pass).observationId();
    }
}
